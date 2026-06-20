package io.github.chinosk.gakumas.localify.hookUtils

import android.app.Activity
import android.net.Uri
import android.util.Log
import io.github.chinosk.gakumas.localify.GakumasHookMain
import io.github.chinosk.gakumas.localify.TAG
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

object FileHotUpdater {
    private const val VERSION_FILE_NAME = "version.txt"

    private fun normalizedEntryName(name: String): String {
        return name.replace('\\', '/').trimStart('/')
    }

    private fun findZipResourceRoot(zipFile: ZipFile): String? {
        val localFilesMarker = "local-files/"
        val entries = zipFile.entries()
        var versionPrefix: String? = null

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = normalizedEntryName(entry.name)
            if (name.isEmpty()) continue

            val localFilesIndex = name.indexOf(localFilesMarker)
            if (localFilesIndex >= 0) {
                return name.substring(0, localFilesIndex)
            }

            if (name == VERSION_FILE_NAME || name.endsWith("/$VERSION_FILE_NAME")) {
                versionPrefix = name.substring(0, name.length - VERSION_FILE_NAME.length)
            }
        }

        return versionPrefix
    }

    private fun safeOutputFile(baseDir: File, relativePath: String): File {
        val targetFile = File(baseDir, relativePath)
        val baseCanonical = baseDir.canonicalFile
        val targetCanonical = targetFile.canonicalFile
        val basePath = baseCanonical.path + File.separator
        if (targetCanonical.path != baseCanonical.path && !targetCanonical.path.startsWith(basePath)) {
            throw IOException("Unsafe zip entry path: $relativePath")
        }
        return targetFile
    }

    private fun extractZipRoot(zipFile: ZipFile, rootPrefix: String, tempDir: File) {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        if (!tempDir.mkdirs()) {
            throw IOException("Failed to create temp resource directory: $tempDir")
        }

        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = normalizedEntryName(entry.name)
            if (!name.startsWith(rootPrefix)) continue

            val relativePath = name.substring(rootPrefix.length)
            if (relativePath.isEmpty()) continue

            val targetFile = safeOutputFile(tempDir, relativePath)
            if (entry.isDirectory) {
                targetFile.mkdirs()
                continue
            }

            targetFile.parentFile?.mkdirs()
            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        val localFilesDir = File(tempDir, "local-files")
        if (!localFilesDir.isDirectory || localFilesDir.listFiles().isNullOrEmpty()) {
            throw IOException("local-files not found in translation zip")
        }
    }

    private fun installExtractedResource(filesDir: File, tempDir: File) {
        val finalBaseDir = File(filesDir, FilesChecker.localizationFilesDir)
        if (!finalBaseDir.exists() && !finalBaseDir.mkdirs()) {
            throw IOException("Failed to create resource directory: $finalBaseDir")
        }

        if (!FilesChecker.cleanAssets(filesDir)) {
            throw IOException("Failed to clean old local-files directory")
        }

        val children = tempDir.listFiles() ?: emptyArray()
        for (child in children) {
            if (child.name == "texture2d") {
                continue
            }

            val target = File(finalBaseDir, child.name)
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Failed to replace old resource path: $target")
            }

            if (child.isDirectory) {
                if (!child.copyRecursively(target, overwrite = true)) {
                    throw IOException("Failed to copy resource directory: ${child.name}")
                }
            }
            else {
                child.copyTo(target, overwrite = true)
            }
        }
    }

    fun getZipResourceVersion(zipFile: String): String? {
        return try {
            val file = File(zipFile)
            if (!file.isFile) return null

            ZipFile(file).use { zip ->
                val rootPrefix = findZipResourceRoot(zip) ?: return null
                val entry = zip.getEntry("${rootPrefix}$VERSION_FILE_NAME") ?: return null
                zip.getInputStream(entry).bufferedReader().use { it.readText().trim() }
            }
        }
        catch (e: Exception) {
            Log.e(TAG, "getZipResourceVersion error: $e")
            null
        }
    }

    fun updateFilesFromZip(activity: Activity, zipFileUri: Uri, filesDir: File, deleteAfterUpdate: Boolean) {
        val tempZipFile = File(filesDir, "translation_update.zip")
        val tempExtractDir = File(filesDir, "${FilesChecker.localizationFilesDir}.tmp")

        try {
            Log.i(TAG, "updateFilesFromZip uri=$zipFileUri deleteAfterUpdate=$deleteAfterUpdate")
            GakumasHookMain.showToast("Updating files from zip...")

            activity.contentResolver.openInputStream(zipFileUri).use { input ->
                if (input == null) {
                    throw IOException("Update zip openInputStream failed")
                }
                FileOutputStream(tempZipFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied translation zip: ${tempZipFile.length()} bytes")

            ZipFile(tempZipFile).use { zip ->
                val rootPrefix = findZipResourceRoot(zip)
                    ?: throw IOException("local-files not found in translation zip")
                extractZipRoot(zip, rootPrefix, tempExtractDir)
            }

            installExtractedResource(filesDir, tempExtractDir)
            tempZipFile.delete()
            tempExtractDir.deleteRecursively()

            if (deleteAfterUpdate) {
                activity.contentResolver.delete(zipFileUri, null, null)
            }
            val installedVersionFile = File(filesDir, "${FilesChecker.localizationFilesDir}/$VERSION_FILE_NAME")
            val localizationFile = File(filesDir, "${FilesChecker.localizationFilesDir}/local-files/localization.json")
            Log.i(TAG, "Translation resources installed: version=" +
                    "${installedVersionFile.takeIf { it.isFile }?.readText()?.trim() ?: "missing"}, " +
                    "localizationJsonExists=${localizationFile.isFile}, " +
                    "localizationJsonSize=${if (localizationFile.isFile) localizationFile.length() else 0}")
            GakumasHookMain.showToast("Update success.")
        }
        catch (e: java.io.FileNotFoundException) {
            tempZipFile.delete()
            tempExtractDir.deleteRecursively()
            Log.i(TAG, "updateFilesFromZip - file not found: $e")
            GakumasHookMain.showToast("Update file not found.")
        }
        catch (e: Exception) {
            tempZipFile.delete()
            tempExtractDir.deleteRecursively()
            Log.e(TAG, "updateFilesFromZip failed: $e")
            GakumasHookMain.showToast("Updating files failed: $e")
        }
    }
}
