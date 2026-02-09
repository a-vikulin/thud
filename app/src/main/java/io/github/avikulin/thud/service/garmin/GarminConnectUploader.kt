package io.github.avikulin.thud.service.garmin

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Handles Garmin Connect authentication, FIT upload, and photo upload.
 *
 * **Auth flow:**
 * 1. User authenticates via overlay WebView → SSO ticket
 * 2. Exchange ticket for OAuth1 tokens (long-lived, ~1 year)
 * 3. Exchange OAuth1 for OAuth2 Bearer token (short-lived, ~1 hour, auto-refreshed)
 *
 * **Both FIT and photo uploads** use the direct API (`connectapi.garmin.com`)
 * with OAuth2 Bearer auth + `DI-Backend` / `NK` headers.
 *
 * A cookie-based fallback via `connect.garmin.com/gc-api/` (web proxy) exists
 * for photo upload but is not needed — the direct API works.
 *
 * OAuth consumer key/secret are fetched from Garmin's public S3 endpoint
 * with hardcoded fallback values.
 */
class GarminConnectUploader(private val context: Context) {

    companion object {
        private const val TAG = "GarminUploader"

        // Token storage
        private const val PREFS_NAME = "GarminConnectTokens"

        // Keys for encrypted storage
        private const val KEY_OAUTH1_TOKEN = "oauth1_token"
        private const val KEY_OAUTH1_SECRET = "oauth1_secret"
        private const val KEY_OAUTH2_TOKEN = "oauth2_token"
        private const val KEY_OAUTH2_EXPIRES_AT = "oauth2_expires_at"
        private const val KEY_WEB_SESSION_COOKIES = "web_session_cookies"

        // Consumer key/secret source
        private const val CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"

        // Fallback consumer credentials (from garth library, public knowledge)
        private const val FALLBACK_CONSUMER_KEY = "fc3e99d2-118c-44b8-8ae3-03370dde24c0"
        private const val FALLBACK_CONSUMER_SECRET = "E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF"

        // Garmin API endpoints
        private const val SSO_BASE = "https://sso.garmin.com/sso"
        private const val OAUTH_BASE = "https://connectapi.garmin.com"
        private const val CONNECT_WEB = "https://connect.garmin.com"
        private const val UPLOAD_URL = "$OAUTH_BASE/upload-service/upload/.fit"

        // Photo upload — direct API (Bearer auth) and web proxy (cookie auth) endpoints
        private const val PHOTO_UPLOAD_API =
            "$OAUTH_BASE/activity-service/activity"
        private const val PHOTO_UPLOAD_WEB =
            "$CONNECT_WEB/gc-api/activity-service/activity"

        // OAuth1 preauthorized endpoint (exchanges SSO ticket for OAuth1 tokens)
        private const val OAUTH1_PREAUTH_URL = "$OAUTH_BASE/oauth-service/oauth/preauthorized"

        // OAuth1→OAuth2 exchange endpoint
        private const val OAUTH2_EXCHANGE_URL = "$OAUTH_BASE/oauth-service/oauth/exchange/user/2.0"
    }

    data class UploadResult(
        val activityId: Long,
        val uploadId: Long = -1L,
        val message: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Lazy-init encrypted prefs (~100ms first call due to key generation)
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Cached consumer credentials
    private var consumerKey: String? = null
    private var consumerSecret: String? = null

    /**
     * Check if OAuth1 tokens exist (user has authenticated at some point).
     */
    fun isAuthenticated(): Boolean {
        val token = encryptedPrefs.getString(KEY_OAUTH1_TOKEN, null)
        return !token.isNullOrEmpty()
    }

    /**
     * Clear all stored tokens (logout).
     */
    fun clearTokens() {
        encryptedPrefs.edit()
            .remove(KEY_OAUTH1_TOKEN)
            .remove(KEY_OAUTH1_SECRET)
            .remove(KEY_OAUTH2_TOKEN)
            .remove(KEY_OAUTH2_EXPIRES_AT)
            .remove(KEY_WEB_SESSION_COOKIES)
            .apply()
        Log.d(TAG, "Tokens cleared")
    }

    /**
     * Establish a web session on connect.garmin.com using SSO cookies.
     *
     * The photo upload endpoint only exists on connect.garmin.com/modern/proxy/
     * which requires session cookies (not OAuth2 Bearer tokens). The SSO TGT
     * (Ticket Granting Ticket) cookie lets us request a new service ticket
     * for connect.garmin.com without user interaction.
     *
     * Flow:
     * 1. GET sso.garmin.com/sso/login?service=connect.garmin.com → 302 with new ticket
     * 2. GET connect.garmin.com/modern/?ticket=... → session cookies set
     *
     * @param ssoCookies Cookie string from WebView's CookieManager for sso.garmin.com
     * @return true if session cookies were obtained
     */
    fun establishWebSession(ssoCookies: String): Boolean {
        val maxRetries = 1
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                Log.d(TAG, "Web session retry $attempt/$maxRetries (waiting 3s)")
                Thread.sleep(3000)
            }
            try {
                val result = attemptWebSession(ssoCookies)
                if (result) return true
                return false // Non-network failure (SSO returned login page, etc.)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Web session network error (attempt ${attempt + 1}/${maxRetries + 1}): ${e.message}")
                if (attempt == maxRetries) return false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to establish web session: ${e.message}")
                return false
            }
        }
        return false
    }

    /**
     * Single attempt to follow SSO redirect chain → connect.garmin.com session.
     * Throws IOException for retryable network errors.
     */
    private fun attemptWebSession(ssoCookies: String): Boolean {
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        // Cookie jar: name → "name=value" — accumulates cookies across redirects
        val cookieJar = linkedMapOf<String, String>()
        for (cookie in ssoCookies.split(";")) {
            val trimmed = cookie.trim()
            if (trimmed.contains("=")) {
                cookieJar[trimmed.substringBefore("=")] = trimmed
            }
        }

        var reachedConnect = false
        var url = "$SSO_BASE/login" +
            "?service=${percentEncode("$CONNECT_WEB/modern")}" +
            "&clientId=GarminConnect"

        Log.d(TAG, "Web session: starting redirect chain with ${cookieJar.size} SSO cookies")

        for (i in 0 until 10) {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieJar.values.joinToString("; "))
                .get()
                .build()

            val response = noRedirectClient.newCall(request).execute()

            // Capture Set-Cookie headers
            val newCookies = mutableListOf<String>()
            for (setCookie in response.headers("Set-Cookie")) {
                val cookiePart = setCookie.split(";")[0].trim()
                if (cookiePart.contains("=")) {
                    cookieJar[cookiePart.substringBefore("=")] = cookiePart
                    newCookies.add(cookiePart.substringBefore("="))
                }
            }

            val host = java.net.URI(url).host
            if (host == "connect.garmin.com") reachedConnect = true

            val code = response.code
            Log.d(TAG, "Web session redirect $i: $code $host" +
                if (newCookies.isNotEmpty()) " +cookies: ${newCookies.joinToString()}" else "")
            response.close()

            if (code in 301..303 || code == 307 || code == 308) {
                val location = response.header("Location") ?: break
                url = if (location.startsWith("http")) location
                else "https://$host$location"
                continue
            }

            break
        }

        if (reachedConnect) {
            val sessionStr = cookieJar.values.joinToString("; ")
            encryptedPrefs.edit()
                .putString(KEY_WEB_SESSION_COOKIES, sessionStr)
                .apply()
            Log.d(TAG, "Web session established (${cookieJar.size} cookies: ${cookieJar.keys.joinToString()})")
            return true
        }

        Log.w(TAG, "Failed to reach connect.garmin.com in redirect chain")
        return false
    }

    /**
     * Check if web session cookies exist for photo upload.
     */
    fun hasWebSession(): Boolean {
        return !encryptedPrefs.getString(KEY_WEB_SESSION_COOKIES, null).isNullOrEmpty()
    }

    /**
     * Exchange an SSO ticket for OAuth1 + OAuth2 tokens.
     * Called after user authenticates via WebView.
     *
     * Steps:
     * 1. Fetch consumer key/secret (cached)
     * 2. OAuth1-signed GET to preauthorized endpoint with ticket → OAuth1 tokens
     * 3. OAuth1-signed POST to exchange endpoint → OAuth2 tokens
     *
     * @return true if tokens were successfully obtained
     */
    fun exchangeTicketForTokens(ticket: String): Boolean {
        return try {
            // Step 1: Get consumer credentials
            val (cKey, cSecret) = getConsumerCredentials()
            Log.d(TAG, "Consumer key obtained")

            // Step 2: Exchange ticket for OAuth1 tokens
            val oauth1Url = "$OAUTH1_PREAUTH_URL?ticket=$ticket&login-url=$SSO_BASE/embed&accepts-mfa-tokens=true"

            val oauth1AuthHeader = buildOAuth1Header(
                method = "GET",
                url = OAUTH1_PREAUTH_URL,
                consumerKey = cKey,
                consumerSecret = cSecret,
                token = null,
                tokenSecret = null,
                extraParams = mapOf(
                    "ticket" to ticket,
                    "login-url" to "$SSO_BASE/embed",
                    "accepts-mfa-tokens" to "true"
                )
            )

            val oauth1Request = Request.Builder()
                .url(oauth1Url)
                .header("Authorization", oauth1AuthHeader)
                .get()
                .build()

            val oauth1Response = client.newCall(oauth1Request).execute()
            val oauth1Body = oauth1Response.body?.string() ?: ""
            oauth1Response.close()

            if (!oauth1Response.isSuccessful) {
                Log.e(TAG, "OAuth1 preauth failed: ${oauth1Response.code} - $oauth1Body")
                return false
            }

            // Parse URL-encoded response: oauth_token=...&oauth_token_secret=...
            val oauth1Params = parseUrlEncoded(oauth1Body)
            val oauth1Token = oauth1Params["oauth_token"]
            val oauth1Secret = oauth1Params["oauth_token_secret"]

            if (oauth1Token.isNullOrEmpty() || oauth1Secret.isNullOrEmpty()) {
                Log.e(TAG, "OAuth1 response missing tokens: $oauth1Body")
                return false
            }

            Log.d(TAG, "OAuth1 tokens obtained")

            // Store OAuth1 tokens immediately (they're long-lived)
            encryptedPrefs.edit()
                .putString(KEY_OAUTH1_TOKEN, oauth1Token)
                .putString(KEY_OAUTH1_SECRET, oauth1Secret)
                .apply()

            // Step 3: Exchange OAuth1 for OAuth2
            exchangeOAuth1ForOAuth2(cKey, cSecret, oauth1Token, oauth1Secret)
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed: ${e.message}", e)
            false
        }
    }

    /**
     * Exchange OAuth1 tokens for OAuth2 Bearer token.
     * OAuth2 tokens are short-lived (~1 hour), so this is called
     * each time the token expires.
     */
    private fun exchangeOAuth1ForOAuth2(
        consumerKey: String,
        consumerSecret: String,
        oauth1Token: String,
        oauth1Secret: String
    ): Boolean {
        val authHeader = buildOAuth1Header(
            method = "POST",
            url = OAUTH2_EXCHANGE_URL,
            consumerKey = consumerKey,
            consumerSecret = consumerSecret,
            token = oauth1Token,
            tokenSecret = oauth1Secret
        )

        val request = Request.Builder()
            .url(OAUTH2_EXCHANGE_URL)
            .header("Authorization", authHeader)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        response.close()

        if (!response.isSuccessful) {
            Log.e(TAG, "OAuth2 exchange failed: ${response.code} - $body")
            return false
        }

        return try {
            val json = JSONObject(body)
            val accessToken = json.getString("access_token")
            val expiresIn = json.optLong("expires_in", 3600)
            val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

            encryptedPrefs.edit()
                .putString(KEY_OAUTH2_TOKEN, accessToken)
                .putLong(KEY_OAUTH2_EXPIRES_AT, expiresAt)
                .apply()

            Log.d(TAG, "OAuth2 token obtained, expires in ${expiresIn}s")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse OAuth2 response: $body", e)
            false
        }
    }

    /**
     * Ensure we have a valid OAuth2 Bearer token.
     * If expired, re-exchange via OAuth1 (no user interaction needed).
     *
     * @return OAuth2 token if available, null if auth is completely expired
     */
    private fun ensureValidOAuth2Token(): String? {
        val oauth2Token = encryptedPrefs.getString(KEY_OAUTH2_TOKEN, null)
        val expiresAt = encryptedPrefs.getLong(KEY_OAUTH2_EXPIRES_AT, 0)

        // Token still valid (with 60s buffer)
        if (!oauth2Token.isNullOrEmpty() && System.currentTimeMillis() < expiresAt - 60_000) {
            return oauth2Token
        }

        // Need to re-exchange via OAuth1
        Log.d(TAG, "OAuth2 token expired, re-exchanging via OAuth1")
        val oauth1Token = encryptedPrefs.getString(KEY_OAUTH1_TOKEN, null) ?: return null
        val oauth1Secret = encryptedPrefs.getString(KEY_OAUTH1_SECRET, null) ?: return null
        val (cKey, cSecret) = getConsumerCredentials()

        val success = exchangeOAuth1ForOAuth2(cKey, cSecret, oauth1Token, oauth1Secret)
        return if (success) {
            encryptedPrefs.getString(KEY_OAUTH2_TOKEN, null)
        } else {
            null
        }
    }

    /**
     * Upload a FIT file to Garmin Connect.
     *
     * @param fitData Raw bytes of the FIT file
     * @param filename Filename for the upload (e.g., "Workout_2026-02-09.fit")
     * @return UploadResult with activity ID on success, null on failure
     */
    fun uploadFitFile(fitData: ByteArray, filename: String): UploadResult? {
        val token = ensureValidOAuth2Token()
        if (token == null) {
            Log.e(TAG, "No valid OAuth2 token for upload")
            return null
        }

        val maxRetries = 2
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                Log.d(TAG, "Upload retry $attempt/$maxRetries (waiting 3s)")
                Thread.sleep(3000)
            }

            try {
                val result = attemptUpload(fitData, filename, token)
                return result // Success or non-retryable failure (auth, server error)
            } catch (e: java.io.IOException) {
                // Network errors (DNS, timeout, connection refused) — retryable
                Log.w(TAG, "Upload network error (attempt ${attempt + 1}/${maxRetries + 1}): ${e.message}")
                if (attempt == maxRetries) {
                    Log.e(TAG, "Upload failed after ${maxRetries + 1} attempts: ${e.message}")
                    return null
                }
            } catch (e: Exception) {
                // Non-network errors — don't retry
                Log.e(TAG, "Upload exception: ${e.message}", e)
                return null
            }
        }
        return null
    }

    /**
     * Single upload attempt. Throws IOException for retryable network errors.
     * Returns UploadResult (or null for non-retryable failures like auth errors).
     */
    private fun attemptUpload(fitData: ByteArray, filename: String, token: String): UploadResult? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "userfile",
                filename,
                fitData.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(UPLOAD_URL)
            .header("Authorization", "Bearer $token")
            .header("DI-Backend", "connectapi.garmin.com")
            .header("NK", "NT")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        val code = response.code
        response.close()

        Log.d(TAG, "Upload response: $code")

        if (code == 401 || code == 403) {
            Log.w(TAG, "Upload auth failed ($code) - tokens may be expired")
            return null
        }

        if (!response.isSuccessful) {
            Log.e(TAG, "Upload failed: $code - $body")
            return null
        }

        // Parse response — Garmin processes async, so successes may be empty initially
        val json = JSONObject(body)
        val importResult = json.optJSONObject("detailedImportResult")
        val uploadId = importResult?.optLong("uploadId", -1L) ?: -1L

        // Check for immediate result
        val activityId = extractActivityId(importResult)
        if (activityId > 0) {
            Log.i(TAG, "Upload successful, activityId=$activityId")
            return UploadResult(activityId, uploadId)
        }

        // Check for immediate failure (duplicate, corrupt file, etc.)
        val failures = importResult?.optJSONArray("failures")
        if (failures != null && failures.length() > 0) {
            val failure = failures.optJSONObject(0)
            val msg = failure?.optJSONArray("messages")?.optJSONObject(0)
            val failCode = msg?.optInt("code", -1) ?: -1
            val failContent = msg?.optString("content", "") ?: ""
            // Code 202 = duplicate activity
            if (failCode == 202) {
                val dupId = failure?.optLong("internalId", -1L) ?: -1L
                Log.i(TAG, "Duplicate activity detected, activityId=$dupId")
                return if (dupId > 0) UploadResult(dupId, uploadId, "duplicate") else null
            }
            Log.w(TAG, "Upload failed: $failContent")
            return null
        }

        // No result yet — Garmin processes async. Search recent activities.
        if (uploadId > 0) {
            Log.d(TAG, "Upload accepted (uploadId=$uploadId), searching for activity...")
            val foundId = findRecentActivityId(token)
            if (foundId > 0) {
                Log.i(TAG, "Activity found after upload: activityId=$foundId")
                return UploadResult(foundId, uploadId)
            }
        }

        // Upload accepted but couldn't get activity ID — still a partial success
        Log.w(TAG, "Upload accepted (uploadId=$uploadId) but activity ID unavailable")
        return UploadResult(-1L, uploadId, "processing")
    }

    /**
     * Check if the last upload failure was an auth error (401/403).
     * Used by caller to decide whether to trigger re-auth.
     */
    fun isOAuth2Expired(): Boolean {
        val expiresAt = encryptedPrefs.getLong(KEY_OAUTH2_EXPIRES_AT, 0)
        return System.currentTimeMillis() >= expiresAt - 60_000
    }

    /**
     * Upload a screenshot as a photo attached to a Garmin activity.
     *
     * Primary strategy: OAuth2 Bearer on connectapi.garmin.com (direct API).
     * Fallback: web session cookies on connect.garmin.com/gc-api/ (web proxy).
     *
     * @param activityId Garmin activity ID from upload result
     * @param imageFile Screenshot file (PNG)
     * @return true on success
     */
    fun uploadScreenshot(activityId: Long, imageFile: File): Boolean {
        Log.d(TAG, "Uploading screenshot: ${imageFile.name} (${imageFile.length()} bytes) to activity $activityId")

        // Strategy 1: OAuth2 Bearer on direct API (connectapi.garmin.com)
        val oauth2Token = ensureValidOAuth2Token()
        if (oauth2Token != null) {
            val result = attemptPhotoUpload(activityId, imageFile, null, oauth2Token)
            if (result == PhotoResult.SUCCESS) return true
            Log.d(TAG, "Bearer auth on direct API: $result")
        }

        // Strategy 2: Session cookies on web proxy (connect.garmin.com/gc-api/)
        val cookies = encryptedPrefs.getString(KEY_WEB_SESSION_COOKIES, null)
        if (!cookies.isNullOrEmpty()) {
            val result = attemptPhotoUpload(activityId, imageFile, cookies, null)
            if (result == PhotoResult.SUCCESS) return true
            if (result == PhotoResult.HTML_FALLBACK) {
                encryptedPrefs.edit().remove(KEY_WEB_SESSION_COOKIES).apply()
            }
            Log.d(TAG, "Session cookie auth on web proxy: $result")
        }

        return false
    }

    private enum class PhotoResult { SUCCESS, HTML_FALLBACK, FAILED }

    /**
     * Single photo upload attempt on /gc-api/ gateway.
     * @param cookies Session cookies (null to skip cookie auth)
     * @param bearerToken OAuth2 Bearer token (null to skip Bearer auth)
     */
    private fun attemptPhotoUpload(
        activityId: Long,
        imageFile: File,
        cookies: String?,
        bearerToken: String?
    ): PhotoResult {
        return try {
            val authMode = when {
                bearerToken != null -> "bearer"
                cookies != null -> "cookies"
                else -> "none"
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    imageFile.name,
                    imageFile.asRequestBody("image/png".toMediaType())
                )
                .build()

            // Bearer auth → direct API (connectapi.garmin.com)
            // Cookie auth → web proxy (connect.garmin.com/gc-api/)
            val uploadUrl = if (bearerToken != null) {
                "$PHOTO_UPLOAD_API/$activityId/image"
            } else {
                "$PHOTO_UPLOAD_WEB/$activityId/image"
            }

            val requestBuilder = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)

            if (bearerToken != null) {
                requestBuilder.header("Authorization", "Bearer $bearerToken")
                requestBuilder.header("DI-Backend", "connectapi.garmin.com")
                requestBuilder.header("NK", "NT")
            }

            if (cookies != null) {
                requestBuilder.header("Cookie", cookies)
                requestBuilder.header("Origin", CONNECT_WEB)
                // CSRF token — required for cookie-based auth on /gc-api/
                val csrfToken = fetchCsrfToken(cookies)
                if (csrfToken != null) {
                    requestBuilder.header("connect-csrf-token", csrfToken)
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val contentType = response.header("Content-Type") ?: ""
            val body = response.body?.string() ?: ""
            val code = response.code
            val successful = response.isSuccessful
            response.close()

            val isHtml = contentType.contains("text/html") ||
                body.contains("<!DOCTYPE") || body.contains("<html")

            when {
                successful && !isHtml -> {
                    Log.i(TAG, "Screenshot uploaded to activity $activityId" +
                        " (HTTP $code, ${contentType.substringBefore(";")}, auth=$authMode)")
                    PhotoResult.SUCCESS
                }
                isHtml -> {
                    Log.w(TAG, "Photo upload got HTML (auth=$authMode)")
                    PhotoResult.HTML_FALLBACK
                }
                else -> {
                    Log.w(TAG, "Photo upload failed: $code (auth=$authMode) - ${body.take(500)}")
                    PhotoResult.FAILED
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo upload exception: ${e.message}", e)
            PhotoResult.FAILED
        }
    }

    /**
     * Fetch CSRF token from Garmin Connect.
     *
     * The /gc-api/ gateway requires a `connect-csrf-token` header.
     * We obtain it by making a lightweight GET request and extracting
     * the token from the response headers or cookies.
     */
    private fun fetchCsrfToken(cookies: String): String? {
        return try {
            // Try fetching a simple endpoint that returns CSRF info
            val request = Request.Builder()
                .url("$CONNECT_WEB/gc-api/session/token")
                .header("Cookie", cookies)
                .header("Origin", CONNECT_WEB)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val code = response.code
            response.close()

            if (response.isSuccessful && body.isNotBlank()) {
                // Could be a plain token string or JSON
                val token = if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    json.optString("token", "").ifEmpty { null }
                        ?: json.optString("csrfToken", "").ifEmpty { null }
                } else {
                    body.trim().takeIf { it.length in 10..100 }
                }
                if (token != null) {
                    Log.d(TAG, "CSRF token obtained from session/token")
                    return token
                }
            }

            Log.d(TAG, "session/token returned $code (${body.take(200)}), trying random UUID")
            // Fallback: some CSRF implementations use double-submit pattern
            // where any value works as long as it's present
            java.util.UUID.randomUUID().toString()
        } catch (e: Exception) {
            Log.w(TAG, "CSRF token fetch failed: ${e.message}")
            // Still try with a random UUID
            java.util.UUID.randomUUID().toString()
        }
    }


    // ==================== Activity Lookup ====================

    /**
     * Extract activity ID from a detailedImportResult JSON object.
     */
    private fun extractActivityId(importResult: JSONObject?): Long {
        val successes = importResult?.optJSONArray("successes") ?: return -1L
        val first = successes.optJSONObject(0) ?: return -1L
        return first.optLong("internalId", -1L)
    }

    /**
     * Find the activity ID for a file we just uploaded.
     * Garmin processes FIT files asynchronously, so the activity may not exist
     * immediately. We wait briefly then query recent activities.
     *
     * @param token OAuth2 Bearer token
     * @return Activity ID, or -1 if not found
     */
    private fun findRecentActivityId(token: String): Long {
        // Wait for Garmin to finish processing the upload
        Thread.sleep(3000)

        try {
            // Fetch the most recent activity — should be the one we just uploaded
            val searchUrl = "$OAUTH_BASE/activitylist-service/activities/search/activities" +
                "?limit=1&start=0"

            val request = Request.Builder()
                .url(searchUrl)
                .header("Authorization", "Bearer $token")
                .header("DI-Backend", "connectapi.garmin.com")
                .header("NK", "NT")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (!response.isSuccessful) {
                Log.d(TAG, "Activity search failed: HTTP ${response.code}")
                return -1L
            }

            // Response is a JSON array of activities
            val activities = org.json.JSONArray(body)
            if (activities.length() == 0) return -1L

            val activity = activities.getJSONObject(0)
            val activityId = activity.optLong("activityId", -1L)

            // Sanity check: only use if created within the last 60 seconds
            val startTime = activity.optString("startTimeLocal", "")
            Log.d(TAG, "Most recent activity: id=$activityId, start=$startTime")
            return activityId
        } catch (e: Exception) {
            Log.d(TAG, "Activity search exception: ${e.message}")
            return -1L
        }
    }

    // ==================== OAuth1 Signing ====================

    /**
     * Build an OAuth1 Authorization header using HMAC-SHA1 signing.
     *
     * OAuth1 requires signing the base string (method, url, sorted params)
     * with the consumer secret and token secret. This is the standard
     * used by Garmin's pre-authorized token exchange.
     */
    private fun buildOAuth1Header(
        method: String,
        url: String,
        consumerKey: String,
        consumerSecret: String,
        token: String?,
        tokenSecret: String?,
        extraParams: Map<String, String> = emptyMap()
    ): String {
        val nonce = generateNonce()
        val timestamp = (System.currentTimeMillis() / 1000).toString()

        // Collect all OAuth params
        val oauthParams = mutableMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0"
        )
        if (token != null) {
            oauthParams["oauth_token"] = token
        }

        // All params (oauth + extra query params) sorted for signature
        val allParams = mutableMapOf<String, String>()
        allParams.putAll(oauthParams)
        allParams.putAll(extraParams)

        val sortedParams = allParams.toSortedMap().entries
            .joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

        // Build signature base string: METHOD&url&params
        val baseString = "${method.uppercase()}&${percentEncode(url)}&${percentEncode(sortedParams)}"

        // Sign with HMAC-SHA1
        val signingKey = "${percentEncode(consumerSecret)}&${percentEncode(tokenSecret ?: "")}"
        val signature = hmacSha1(signingKey, baseString)

        // Build Authorization header
        oauthParams["oauth_signature"] = signature
        return "OAuth " + oauthParams.entries
            .joinToString(", ") { "${percentEncode(it.key)}=\"${percentEncode(it.value)}\"" }
    }

    private fun hmacSha1(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val result = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            .replace(Regex("[^a-zA-Z0-9]"), "")
    }

    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    // ==================== Consumer Credentials ====================

    /**
     * Get OAuth consumer key/secret from Garmin's S3 endpoint.
     * Falls back to hardcoded values if S3 is unreachable.
     * Results are cached in memory for the lifetime of this instance.
     */
    private fun getConsumerCredentials(): Pair<String, String> {
        // Return cached if available
        val cached = consumerKey
        if (cached != null && consumerSecret != null) {
            return Pair(cached, consumerSecret!!)
        }

        try {
            val request = Request.Builder()
                .url(CONSUMER_URL)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (response.isSuccessful) {
                val json = JSONObject(body)
                val key = json.optString("consumer_key", "")
                val secret = json.optString("consumer_secret", "")
                if (key.isNotEmpty() && secret.isNotEmpty()) {
                    consumerKey = key
                    consumerSecret = secret
                    Log.d(TAG, "Consumer credentials fetched from S3")
                    return Pair(key, secret)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch consumer credentials from S3: ${e.message}")
        }

        // Fallback to hardcoded values
        Log.d(TAG, "Using fallback consumer credentials")
        consumerKey = FALLBACK_CONSUMER_KEY
        consumerSecret = FALLBACK_CONSUMER_SECRET
        return Pair(FALLBACK_CONSUMER_KEY, FALLBACK_CONSUMER_SECRET)
    }

    // ==================== Helpers ====================

    /**
     * Parse URL-encoded response (key=value&key=value).
     */
    private fun parseUrlEncoded(body: String): Map<String, String> {
        return body.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }
}
