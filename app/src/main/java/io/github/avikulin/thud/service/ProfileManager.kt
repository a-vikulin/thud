package io.github.avikulin.thud.service

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Data class representing a user profile.
 */
data class UserProfile(
    val id: String,
    val name: String,
    val pinHash: String?,
    val createdAt: Long
)

/**
 * Singleton managing the profile registry.
 *
 * Registry lives in its own SharedPreferences ("tHUD_profiles"), completely
 * separate from any user's preferences. Each profile gets isolated:
 * - Room DB: files/profiles/<id>/treadmillhud.db
 * - SharedPreferences: shared_prefs/TreadmillHUD_<id>.xml (flat naming, no path separators)
 * - Garmin tokens: shared_prefs/GarminConnectTokens_<id>.xml
 * - Run persistence: files/profiles/<id>/active_run.json
 * - Pending Garmin: files/profiles/<id>/pending_garmin_upload.fit
 * - Downloads: Downloads/tHUD/<name>/
 */
object ProfileManager {

    private const val TAG = "ProfileManager"

    private const val REGISTRY_PREFS = "tHUD_profiles"
    private const val KEY_PROFILES_JSON = "profiles_json"
    private const val KEY_ACTIVE_ID = "active_profile_id"
    private const val KEY_AUTHENTICATED_SWITCH = "authenticated_switch"

    const val GUEST_PROFILE_ID = "guest"
    const val GUEST_PROFILE_NAME = "Guest"

    private const val DB_FILENAME = "treadmillhud.db"

    // ==================== Path Derivation ====================

    /**
     * SharedPreferences name for a profile's app settings.
     * Uses flat naming (no path separators) because Android's ContextImpl
     * rejects path separators in SharedPreferences names.
     * Android creates: shared_prefs/TreadmillHUD_<id>.xml
     */
    fun prefsName(profileId: String): String = "TreadmillHUD_$profileId"

    /**
     * SharedPreferences name for a profile's Garmin OAuth tokens.
     * Android creates: shared_prefs/GarminConnectTokens_<id>.xml
     */
    fun garminPrefsName(profileId: String): String = "GarminConnectTokens_$profileId"

    /**
     * Internal storage directory for a profile's files (DB, persistence, etc.).
     */
    fun profileDir(context: Context, profileId: String): File {
        val dir = File(context.filesDir, "profiles/$profileId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Absolute path to a profile's Room database file.
     * Room accepts absolute paths and uses them directly (not under databases/).
     */
    fun dbPath(context: Context, profileId: String): String =
        File(profileDir(context, profileId), DB_FILENAME).absolutePath

    /**
     * Downloads subfolder name for a profile (the display name).
     */
    fun downloadsSubfolder(profileName: String): String = profileName

    // ==================== Registry Operations ====================

    /**
     * Ensure the profile registry exists.
     * MUST be called early in HUDService.onCreate() BEFORE any SharedPreferences
     * or Room DB access, because migration moves files that haven't been cached yet.
     */
    fun ensureRegistryExists(context: Context) {
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        if (registry.contains(KEY_PROFILES_JSON)) return

        val oldDb = context.getDatabasePath(DB_FILENAME)
        if (oldDb.exists()) {
            Log.i(TAG, "Existing install detected — migrating to profile system")
            migrateExistingData(context, registry)
        } else {
            Log.i(TAG, "Fresh install — creating default profiles")
            createFreshInstall(context, registry)
        }
    }

    fun getActiveProfileId(context: Context): String {
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        return registry.getString(KEY_ACTIVE_ID, GUEST_PROFILE_ID) ?: GUEST_PROFILE_ID
    }

    fun getActiveProfile(context: Context): UserProfile {
        val activeId = getActiveProfileId(context)
        return getAllProfiles(context).firstOrNull { it.id == activeId }
            ?: getAllProfiles(context).first() // Fallback to first profile
    }

    fun getAllProfiles(context: Context): List<UserProfile> {
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        val json = registry.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        return parseProfiles(json).sortedWith(compareBy<UserProfile> {
            // Guest always first
            if (it.id == GUEST_PROFILE_ID) 0 else 1
        }.thenBy { it.name })
    }

    fun createProfile(context: Context, name: String, pin: String?): UserProfile {
        val id = if (name == GUEST_PROFILE_NAME && getAllProfiles(context).none { it.id == GUEST_PROFILE_ID }) {
            GUEST_PROFILE_ID
        } else {
            UUID.randomUUID().toString().take(8)
        }
        val profile = UserProfile(
            id = id,
            name = name,
            pinHash = pin?.let { hashPin(it) },
            createdAt = System.currentTimeMillis()
        )

        // Ensure profile directory exists (for DB, persistence files)
        profileDir(context, id)

        // Add to registry
        val profiles = getAllProfiles(context).toMutableList()
        profiles.add(profile)
        saveProfiles(context, profiles)

        Log.i(TAG, "Created profile: name=$name, id=$id")
        return profile
    }

    fun renameProfile(context: Context, profileId: String, newName: String) {
        if (profileId == GUEST_PROFILE_ID) return
        val profiles = getAllProfiles(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return

        val oldName = profiles[index].name
        profiles[index] = profiles[index].copy(name = newName)
        saveProfiles(context, profiles)

        Log.i(TAG, "Renamed profile $profileId: $oldName → $newName")

        // Rename Downloads subfolder via MediaStore (background — may be slow with many files)
        val appContext = context.applicationContext
        thread(name = "ProfileRenameDownloads") {
            renameDownloadsFolder(appContext, oldName, newName)
        }
    }

    fun deleteProfile(context: Context, profileId: String) {
        if (profileId == GUEST_PROFILE_ID) return

        val profiles = getAllProfiles(context).toMutableList()
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        profiles.removeAll { it.id == profileId }
        saveProfiles(context, profiles)

        // Clean up profile files (DB, persistence, etc.)
        val dir = File(context.filesDir, "profiles/$profileId")
        if (dir.exists()) dir.deleteRecursively()

        // Clean up SharedPreferences files (flat naming)
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        File(sharedPrefsDir, "${prefsName(profileId)}.xml").delete()
        File(sharedPrefsDir, "${garminPrefsName(profileId)}.xml").delete()

        // Clean up Downloads folder
        deleteDownloadsFolder(context, profile.name)

        // If active profile was deleted, switch to Guest
        if (getActiveProfileId(context) == profileId) {
            setActiveProfile(context, GUEST_PROFILE_ID)
        }

        Log.i(TAG, "Deleted profile: ${profile.name} ($profileId)")
    }

    fun setActiveProfile(context: Context, profileId: String) {
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        registry.edit().putString(KEY_ACTIVE_ID, profileId).apply()
        Log.d(TAG, "Active profile set to: $profileId")
    }

    /**
     * Mark the next service startup as an authenticated switch (user entered PIN).
     * Prevents the Guest auto-fallback from firing on restart.
     */
    fun setAuthenticatedSwitch(context: Context) {
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        registry.edit().putBoolean(KEY_AUTHENTICATED_SWITCH, true).apply()
    }

    /**
     * Consume (read + clear) the authenticated switch flag.
     * Returns true if the flag was set — meaning the user just authenticated.
     */
    fun consumeAuthenticatedSwitch(context: Context): Boolean {
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        val was = registry.getBoolean(KEY_AUTHENTICATED_SWITCH, false)
        if (was) registry.edit().remove(KEY_AUTHENTICATED_SWITCH).apply()
        return was
    }

    // ==================== PIN Operations ====================

    fun verifyPin(context: Context, profileId: String, enteredPin: String): Boolean {
        val profile = getAllProfiles(context).firstOrNull { it.id == profileId } ?: return false
        return profile.pinHash == hashPin(enteredPin)
    }

    fun hasPin(context: Context, profileId: String): Boolean {
        val profile = getAllProfiles(context).firstOrNull { it.id == profileId } ?: return false
        return profile.pinHash != null
    }

    fun setPin(context: Context, profileId: String, pin: String?) {
        val profiles = getAllProfiles(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return
        profiles[index] = profiles[index].copy(pinHash = pin?.let { hashPin(it) })
        saveProfiles(context, profiles)
    }

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in 4..8 && pin.all { it.isDigit() }

    // ==================== Migration ====================

    /**
     * Migrate an existing single-user install into the profile system.
     * CRITICAL: Must run BEFORE any getSharedPreferences() call for "TreadmillHUD"
     * or Room DB access, because Android caches these on first access.
     */
    private fun migrateExistingData(context: Context, registry: android.content.SharedPreferences) {
        val userId = UUID.randomUUID().toString().take(8)
        val userProfile = UserProfile(
            id = userId,
            name = "User",
            pinHash = null,
            createdAt = System.currentTimeMillis()
        )
        val guestProfile = UserProfile(
            id = GUEST_PROFILE_ID,
            name = GUEST_PROFILE_NAME,
            pinHash = null,
            createdAt = System.currentTimeMillis()
        )

        // Create profile directories
        val userDir = profileDir(context, userId)
        profileDir(context, GUEST_PROFILE_ID)

        // 1. Move Room database files
        val dbDir = context.getDatabasePath(DB_FILENAME).parentFile
        val dbFile = File(dbDir, DB_FILENAME)
        if (dbFile.exists()) {
            dbFile.renameTo(File(userDir, DB_FILENAME))
            // Move WAL and SHM companions if they exist
            File(dbDir, "$DB_FILENAME-shm").let { if (it.exists()) it.renameTo(File(userDir, "$DB_FILENAME-shm")) }
            File(dbDir, "$DB_FILENAME-wal").let { if (it.exists()) it.renameTo(File(userDir, "$DB_FILENAME-wal")) }
            Log.d(TAG, "Migrated database files to profile dir")
        }

        // 2. Move run persistence file
        File(context.filesDir, "active_run.json").let {
            if (it.exists()) it.renameTo(File(userDir, "active_run.json"))
        }
        File(context.filesDir, "active_run.json.tmp").let {
            if (it.exists()) it.renameTo(File(userDir, "active_run.json.tmp"))
        }

        // 3. Move pending Garmin upload file
        File(context.filesDir, "pending_garmin_upload.fit").let {
            if (it.exists()) it.renameTo(File(userDir, "pending_garmin_upload.fit"))
        }

        // 4. Rename SharedPreferences files to flat profile-specific names (on disk, before loaded)
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")

        File(sharedPrefsDir, "TreadmillHUD.xml").let {
            if (it.exists()) it.renameTo(File(sharedPrefsDir, "${prefsName(userId)}.xml"))
        }
        File(sharedPrefsDir, "GarminConnectTokens.xml").let {
            if (it.exists()) it.renameTo(File(sharedPrefsDir, "${garminPrefsName(userId)}.xml"))
        }

        // 5. Move Downloads/tHUD/ files into user's subfolder
        migrateDownloadsFolder(context, userProfile.name)

        // 6. Save registry
        val profiles = listOf(guestProfile, userProfile)
        val profilesJson = serializeProfiles(profiles)
        registry.edit()
            .putString(KEY_PROFILES_JSON, profilesJson)
            .putString(KEY_ACTIVE_ID, userId)
            .apply()

        Log.i(TAG, "Migration complete: created User profile ($userId) + Guest")
    }

    /**
     * Create profiles for a fresh installation (no existing data).
     */
    private fun createFreshInstall(context: Context, registry: android.content.SharedPreferences) {
        val userId = UUID.randomUUID().toString().take(8)
        val userProfile = UserProfile(
            id = userId,
            name = "User",
            pinHash = null,
            createdAt = System.currentTimeMillis()
        )
        val guestProfile = UserProfile(
            id = GUEST_PROFILE_ID,
            name = GUEST_PROFILE_NAME,
            pinHash = null,
            createdAt = System.currentTimeMillis()
        )

        // Create profile directories
        profileDir(context, userId)
        profileDir(context, GUEST_PROFILE_ID)

        val profiles = listOf(guestProfile, userProfile)
        val profilesJson = serializeProfiles(profiles)
        registry.edit()
            .putString(KEY_PROFILES_JSON, profilesJson)
            .putString(KEY_ACTIVE_ID, userId)
            .apply()

        Log.i(TAG, "Fresh install: created User ($userId) + Guest")
    }

    // ==================== Downloads Folder Management ====================

    /**
     * During migration, move all existing Downloads/tHUD/ files into Downloads/tHUD/<name>/.
     * Uses MediaStore RELATIVE_PATH update — no file copy needed.
     */
    private fun migrateDownloadsFolder(context: Context, profileName: String) {
        try {
            val resolver = context.contentResolver
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/tHUD/%")
            val cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH),
                selection, args, null
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val oldPath = it.getString(1)
                    // Insert profile name after "tHUD/"
                    val newPath = oldPath.replaceFirst("tHUD/", "tHUD/$profileName/")
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.RELATIVE_PATH, newPath)
                    }
                    resolver.update(uri, cv, null, null)
                    count++
                }
                Log.d(TAG, "Migrated $count Downloads files to tHUD/$profileName/")
            }
            // Clean up empty old directories (e.g. tHUD/screenshots/) left behind by MediaStore moves
            val tHudDir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "tHUD")
            tHudDir.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name != profileName) {
                    deleteEmptyDirRecursively(child)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate Downloads folder: ${e.message}", e)
        }
    }

    /**
     * Rename a profile's Downloads subfolder via MediaStore.
     */
    private fun renameDownloadsFolder(context: Context, oldName: String, newName: String) {
        try {
            val resolver = context.contentResolver
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/tHUD/$oldName/%")
            val cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH),
                selection, args, null
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val oldPath = it.getString(1)
                    val newPath = oldPath.replaceFirst("tHUD/$oldName/", "tHUD/$newName/")
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.RELATIVE_PATH, newPath)
                    }
                    resolver.update(uri, cv, null, null)
                    count++
                }
                Log.d(TAG, "Renamed $count Downloads files: tHUD/$oldName/ → tHUD/$newName/")
            }
            // Clean up empty old directories left behind by MediaStore moves
            deleteEmptyDirRecursively(File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "tHUD/$oldName"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename Downloads folder: ${e.message}", e)
        }
    }

    /**
     * Delete all Downloads files for a profile.
     */
    private fun deleteDownloadsFolder(context: Context, profileName: String) {
        try {
            val resolver = context.contentResolver
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/tHUD/$profileName/%")
            val count = resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                selection, args
            )
            // Clean up empty directories left behind by MediaStore deletes
            deleteEmptyDirRecursively(File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "tHUD/$profileName"))
            Log.d(TAG, "Deleted $count Downloads files for profile: $profileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Downloads folder: ${e.message}", e)
        }
    }

    /**
     * Recursively delete empty directories bottom-up.
     * Only deletes directories that are empty (or contain only empty subdirectories).
     * Leaves non-empty directories and files untouched.
     */
    private fun deleteEmptyDirRecursively(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) deleteEmptyDirRecursively(child)
        }
        // Delete this dir only if it's now empty
        if (dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        }
    }

    // ==================== Serialization ====================

    private fun parseProfiles(json: String): List<UserProfile> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                UserProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    pinHash = if (obj.has("pinHash")) obj.getString("pinHash") else null,
                    createdAt = obj.optLong("createdAt", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profiles: ${e.message}", e)
            emptyList()
        }
    }

    private fun serializeProfiles(profiles: List<UserProfile>): String {
        val arr = JSONArray()
        profiles.forEach { profile ->
            arr.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                if (profile.pinHash != null) put("pinHash", profile.pinHash)
                put("createdAt", profile.createdAt)
            })
        }
        return arr.toString()
    }

    private fun saveProfiles(context: Context, profiles: List<UserProfile>) {
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        registry.edit()
            .putString(KEY_PROFILES_JSON, serializeProfiles(profiles))
            .apply()
    }
}
