package io.github.chinosk.gakumas.localify.mainUtils

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.chinosk.gakumas.localify.GakumasHookMain
import io.github.chinosk.gakumas.localify.TAG
import io.github.chinosk.gakumas.localify.models.Asset
import io.github.chinosk.gakumas.localify.models.GithubReleaseModel
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

object TextureResourceUpdater {
    private const val CACHE_DIR = "remote_texture_files"
    private const val CACHE_ZIP_NAME = "texture.zip"
    private const val CACHE_VERSION_NAME = "texture_version.txt"
    private const val TEXTURE_DIR = "gakumas-local/texture2d"
    private const val VERSION_FILE_NAME = "texture_version.txt"

    private data class TextureZipRoot(val prefix: String, val containsTexture2dDir: Boolean)

    private fun textureDir(context: Context): File {
        return File(context.filesDir, TEXTURE_DIR)
    }

    fun getCachedZipFile(context: Context): File {
        return File(context.filesDir, "$CACHE_DIR/$CACHE_ZIP_NAME")
    }

    fun getLocalVersion(context: Context): String? {
        val versionFile = File(context.filesDir, "$CACHE_DIR/$CACHE_VERSION_NAME")
        if (versionFile.exists()) {
            versionFile.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    fun getInstalledVersion(context: Context): String? {
        val versionFile = File(textureDir(context), VERSION_FILE_NAME)
        if (!versionFile.exists()) {
            return null
        }
        return versionFile.readText().trim()
    }

    private fun ensureCacheDir(context: Context): File {
        val basePath = File(context.filesDir, CACHE_DIR)
        if (!basePath.exists()) {
            basePath.mkdirs()
        }
        return basePath
    }

    private fun saveCachedVersion(context: Context, version: String) {
        val versionFile = File(ensureCacheDir(context), CACHE_VERSION_NAME)
        versionFile.writeText(version)
    }

    private fun findTextureZipRoot(zipFile: ZipFile): TextureZipRoot? {
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue

            val name = entry.name.replace('\\', '/').trimStart('/')
            val textureVersionMarker = "texture2d/$VERSION_FILE_NAME"
            if (name.endsWith(textureVersionMarker)) {
                return TextureZipRoot(name.substring(0, name.length - textureVersionMarker.length), true)
            }
            if (name == VERSION_FILE_NAME || name.endsWith("/$VERSION_FILE_NAME")) {
                val prefix = name.substring(0, name.length - VERSION_FILE_NAME.length)
                return TextureZipRoot(prefix, false)
            }
        }
        return null
    }

    private fun readTextureVersion(zipFile: ZipFile, root: TextureZipRoot): String? {
        val versionPath = if (root.containsTexture2dDir) {
            "${root.prefix}texture2d/$VERSION_FILE_NAME"
        } else {
            "${root.prefix}$VERSION_FILE_NAME"
        }
        val entry = zipFile.getEntry(versionPath) ?: return null
        return zipFile.getInputStream(entry).bufferedReader().use { it.readText().trim() }
    }

    private fun validateTextureZip(zipFile: File): String {
        ZipFile(zipFile).use { zip ->
            val root = findTextureZipRoot(zip) ?: throw IOException("texture_version.txt not found in texture zip")
            return readTextureVersion(zip, root) ?: throw IOException("texture_version.txt is empty")
        }
    }

    private fun findTextureZipAsset(releaseData: GithubReleaseModel): Asset? {
        val zipAssets = releaseData.assets.filter { it.name.endsWith(".zip", ignoreCase = true) }
        return zipAssets.firstOrNull { it.name.equals("texture2d.zip", ignoreCase = true) }
            ?: zipAssets.firstOrNull()
    }

    private fun safeOutputFile(baseDir: File, relativePath: String): File? {
        val targetFile = File(baseDir, relativePath)
        val baseCanonical = baseDir.canonicalFile
        val targetCanonical = targetFile.canonicalFile
        val basePath = baseCanonical.path + File.separator
        if (targetCanonical.path != baseCanonical.path && !targetCanonical.path.startsWith(basePath)) {
            return null
        }
        return targetFile
    }

    private fun installTextureZip(context: Context, zipFile: File, version: String? = null) {
        ZipFile(zipFile).use { zip ->
            val root = findTextureZipRoot(zip) ?: throw IOException("texture_version.txt not found in texture zip")
            val installedVersion = version ?: readTextureVersion(zip, root)
                ?: throw IOException("texture_version.txt is empty")
            val targetDir = textureDir(context)
            val tempDir = File(context.filesDir, "$TEXTURE_DIR.tmp")

            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            tempDir.mkdirs()

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.replace('\\', '/').trimStart('/')

                val relativePath = if (root.containsTexture2dDir) {
                    val rootPath = "${root.prefix}texture2d/"
                    if (!name.startsWith(rootPath)) continue
                    name.substring(rootPath.length)
                } else {
                    if (!name.startsWith(root.prefix)) continue
                    name.substring(root.prefix.length)
                }

                if (relativePath.isEmpty()) continue
                val targetFile = safeOutputFile(tempDir, relativePath) ?: continue

                if (entry.isDirectory) {
                    targetFile.mkdirs()
                    continue
                }

                targetFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            File(tempDir, VERSION_FILE_NAME).writeText(installedVersion)

            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            if (!tempDir.renameTo(targetDir)) {
                tempDir.copyRecursively(targetDir, overwrite = true)
                tempDir.deleteRecursively()
            }
        }
    }

    fun updateTextureFilesFromZip(activity: Activity, zipFileUri: Uri, filesDir: File,
                                  deleteAfterUpdate: Boolean) {
        try {
            GakumasHookMain.showToast("Updating texture files from zip...")

            val tempZipFile = File(filesDir, "texture_update.zip")
            activity.contentResolver.openInputStream(zipFileUri).use { input ->
                if (input == null) {
                    Log.e(TAG, "texture zip openInputStream failed.")
                    GakumasHookMain.showToast("Texture update file not found.")
                    return
                }
                tempZipFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            installTextureZip(activity, tempZipFile)
            Log.i(TAG, "Texture zip installed into ${File(filesDir, TEXTURE_DIR).absolutePath}, " +
                    "version=${getInstalledVersion(activity)}")
            tempZipFile.delete()

            if (deleteAfterUpdate) {
                activity.contentResolver.delete(zipFileUri, null, null)
            }
            GakumasHookMain.showToast("Texture update success.")
        }
        catch (e: java.io.FileNotFoundException) {
            Log.i(TAG, "updateTextureFilesFromZip - file not found: $e")
            GakumasHookMain.showToast("Texture update file not found.")
        }
        catch (e: Exception) {
            Log.e(TAG, "updateTextureFilesFromZip failed: $e")
            GakumasHookMain.showToast("Texture update failed: $e")
        }
    }

    fun checkUpdateTextureAssets(context: Context, apiURL: String,
                                 onFailed: (Int, String) -> Unit,
                                 onResult: (data: GithubReleaseModel, localVersion: String?) -> Unit) {
        runCatching {
            val request = Request.Builder()
                .url(apiURL)
                .build()
            FileDownloader.requestGet(request, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onFailed(-1, e.toString())
                }

                override fun onResponse(call: Call, response: Response) {
                    runCatching {
                        response.use {
                            if (!response.isSuccessful) throw IOException("Unexpected code $response")

                            val responseBody = response.body?.string()
                            if (responseBody != null) {
                                val json = Json { ignoreUnknownKeys = true }
                                val releaseData = json.decodeFromString<GithubReleaseModel>(responseBody)
                                onResult(releaseData, getLocalVersion(context))
                            } else {
                                onFailed(-1, "Response body is null")
                            }
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "checkUpdateTextureAssets failed", e)
                        onFailed(-1, e.toString())
                    }
                }
            })
        }.onFailure { e ->
            Log.e(TAG, "checkUpdateTextureAssets failed", e)
            onFailed(-1, e.toString())
        }
    }

    fun updateTextureAssets(context: Context, apiURL: String,
                            deleteAfterUpdate: Boolean,
                            onDownload: (Float, downloaded: Long, size: Long) -> Unit,
                            onFailed: (Int, String) -> Unit,
                            onSuccess: (version: String, changed: Boolean) -> Unit) {
        runCatching {
            val request = Request.Builder()
                .url(apiURL)
                .build()
            FileDownloader.requestGet(request, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onFailed(-1, e.toString())
                }

                override fun onResponse(call: Call, response: Response) {
                    runCatching {
                        response.use {
                            if (!response.isSuccessful) throw IOException("Unexpected code $response")

                            val responseBody = response.body?.string()
                            if (responseBody != null) {
                                val json = Json { ignoreUnknownKeys = true }
                                val releaseData = json.decodeFromString<GithubReleaseModel>(responseBody)
                                val releaseVersion = releaseData.tag_name
                                val localVersion = getLocalVersion(context)
                                if (releaseVersion == localVersion) {
                                    onSuccess(releaseVersion, false)
                                    return
                                }

                                val zipAsset = findTextureZipAsset(releaseData)
                                if (zipAsset == null) {
                                    onFailed(-1, "No zip asset found")
                                    return
                                }

                                val cacheFile = getCachedZipFile(context)
                                FileDownloader.downloadFileToPath(zipAsset.browser_download_url,
                                    cacheFile,
                                    onDownload, {
                                        runCatching {
                                            saveCachedVersion(context, releaseVersion)
                                            val zipVersion = validateTextureZip(cacheFile)
                                            if (zipVersion != releaseVersion) {
                                                cacheFile.delete()
                                                File(context.filesDir, "$CACHE_DIR/$CACHE_VERSION_NAME").delete()
                                                throw IOException("texture_version.txt ($zipVersion) differs from release tag ($releaseVersion)")
                                            }
                                            Log.i(TAG, "Texture zip cached: ${cacheFile.absolutePath}, " +
                                                    "size=${cacheFile.length()}, version=$releaseVersion")
                                            onSuccess(releaseVersion, true)
                                        }.onFailure { e ->
                                            Log.e(TAG, "save texture zip failed", e)
                                            onFailed(-1, e.toString())
                                        }
                                    },
                                    onFailed)
                            } else {
                                onFailed(-1, "Response body is null")
                            }
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "updateTextureAssets failed", e)
                        onFailed(-1, e.toString())
                    }
                }
            })
        }.onFailure { e ->
            Log.e(TAG, "updateTextureAssets failed", e)
            onFailed(-1, e.toString())
        }
    }
}
