package io.github.avikulin.thud.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Centralized helper for exporting files to Downloads/tHUD folder.
 * Uses MediaStore API for Android 10+ compatibility.
 *
 * Note: This helper requires writing to a temp file first, then copying to MediaStore.
 * Not suitable for shell commands (like `screencap`) that need to write directly to
 * a file path - they cannot access the app's private cache directory.
 */
object FileExportHelper {

    private const val BASE_FOLDER = "tHUD"

    /**
     * Active profile's subfolder name (display name, e.g. "Alex").
     * Set by HUDService.onCreate() on startup and profile switch.
     */
    @Volatile
    var activeProfileSubfolder: String = ""

    /**
     * Subfolders within tHUD/<profile>/ directory.
     */
    object Subfolder {
        const val ROOT = ""           // Downloads/tHUD/<profile>/
        const val SCREENSHOTS = "screenshots"  // Downloads/tHUD/<profile>/screenshots/
        const val EXPORT = "export"   // Downloads/tHUD/<profile>/export/
        const val IMPORT = "import"   // Downloads/tHUD/<profile>/import/
    }

    /**
     * Get the relative path for MediaStore.
     * With profile: "Download/tHUD/Alex" or "Download/tHUD/Alex/screenshots"
     */
    fun getRelativePath(subfolder: String = Subfolder.ROOT): String {
        val profilePart = activeProfileSubfolder
        val base = if (profilePart.isEmpty()) BASE_FOLDER else "$BASE_FOLDER/$profilePart"
        return if (subfolder.isEmpty()) {
            "${Environment.DIRECTORY_DOWNLOADS}/$base"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$base/$subfolder"
        }
    }

    /**
     * Get display path for user feedback.
     * With profile: "Downloads/tHUD/Alex/file.fit"
     */
    fun getDisplayPath(filename: String, subfolder: String = Subfolder.ROOT): String {
        val profilePart = activeProfileSubfolder
        val base = if (profilePart.isEmpty()) BASE_FOLDER else "$BASE_FOLDER/$profilePart"
        return if (subfolder.isEmpty()) {
            "Downloads/$base/$filename"
        } else {
            "Downloads/$base/$subfolder/$filename"
        }
    }

    /**
     * Save a file to Downloads/tHUD using MediaStore.
     *
     * @param context Android context
     * @param sourceFile Source file to copy (will not be deleted)
     * @param filename Target filename
     * @param mimeType MIME type of the file
     * @param subfolder Subfolder within tHUD (use Subfolder constants)
     * @return Display path if successful, null otherwise
     */
    fun saveToDownloads(
        context: Context,
        sourceFile: File,
        filename: String,
        mimeType: String,
        subfolder: String = Subfolder.ROOT
    ): String? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return null
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, getRelativePath(subfolder))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return null

            // Mark file as complete
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            getDisplayPath(filename, subfolder)
        } catch (e: Exception) {
            // Clean up on failure
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Get a temporary file path for tools that need to write to a file first.
     * Caller is responsible for deleting the temp file after use.
     */
    fun getTempFile(context: Context, filename: String): File {
        return File(context.cacheDir, filename)
    }
}
