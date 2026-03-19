package io.github.chinosk.gakumas.localify.hookUtils

import android.content.res.XModuleResources
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader


object FilesChecker {
    lateinit var filesDir: File
    lateinit var modulePath: String
    val localizationFilesDir = "gakumas-local"
    var filesUpdated = false

    fun initAndCheck(fileDir: File, modulePath: String) {
        initDir(fileDir, modulePath)

        checkFiles()
    }

    fun initDir(fileDir: File, modulePath: String) {
        this.filesDir = fileDir
        this.modulePath = modulePath
    }

    fun checkFiles() {
        val installedVersion = getInstalledVersion()
        val pluginVersion = getPluginVersion()
        Log.d("GakumasLocal", "installedVer: $installedVersion, pluginVer: $pluginVersion")

        val localFilesDir = getLocalFilesDir(filesDir)
        val localFilesMissing = !localFilesDir.exists() || localFilesDir.listFiles().isNullOrEmpty()

        if (localFilesMissing || pluginVersion != installedVersion) {
            updateFiles()
        }
    }

    fun updateFiles() {
        if (filesUpdated) return
        filesUpdated = true

        Log.i("GakumasLocal", "Updating files...")
        val pluginBasePath = File(filesDir, localizationFilesDir)
        if (!pluginBasePath.exists()) {
            pluginBasePath.mkdirs()
        }

        if (!cleanAssets(filesDir)) {
            Log.e("GakumasLocal", "Failed to clean local assets before built-in update")
            return
        }

        val assets = XModuleResources.createInstance(modulePath, null).assets
        fun forAllAssetFiles(
            basePath: String,
            action: (String, InputStream?) -> Unit
        ) {
            val assetFiles = assets.list(basePath)!!
            for (file in assetFiles) {
                try {
                    assets.open("$basePath/$file")
                } catch (e: IOException) {
                    action("$basePath/$file", null)
                    forAllAssetFiles("$basePath/$file", action)
                    continue
                }.use {
                    action("$basePath/$file", it)
                }
            }
        }
        forAllAssetFiles(localizationFilesDir) { path, file ->
            val outFile = File(filesDir, path)
            if (file == null) {
                outFile.mkdirs()
            } else {
                outFile.outputStream().use { out ->
                    file.copyTo(out)
                }
            }
        }

        Log.i("GakumasLocal", "Updated")
    }

    fun getPluginVersion(): String {
        val assets = XModuleResources.createInstance(modulePath, null).assets

        for (i in assets.list(localizationFilesDir)!!) {
            if (i.toString() == "version.txt") {
                val stream = assets.open("$localizationFilesDir/$i")
                return convertToString(stream).trim()
            }
        }
        return "0.0"
    }

    fun getInstalledVersion(): String {
        val pluginFilesDir = File(filesDir, localizationFilesDir)
        if (!pluginFilesDir.exists()) return "0.0"

        val versionFile = File(pluginFilesDir, "version.txt")
        if (!versionFile.exists()) return "0.0"
        return versionFile.readText().trim()
    }

    fun convertToString(inputStream: InputStream?): String {
        val stringBuilder = StringBuilder()
        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        return stringBuilder.toString()
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    val success = deleteRecursively(child)
                    if (!success) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }

    private fun getLocalFilesDir(baseFilesDir: File): File {
        val pluginBasePath = File(baseFilesDir, localizationFilesDir)
        if (!pluginBasePath.exists()) {
            pluginBasePath.mkdirs()
        }
        return File(pluginBasePath, "local-files")
    }

    fun cleanAssets(baseFilesDir: File): Boolean {
        val localFilesDir = getLocalFilesDir(baseFilesDir)

        if (localFilesDir.exists() && !deleteRecursively(localFilesDir)) {
            Log.e("GakumasLocal", "Failed to delete old local-files directory: $localFilesDir")
            return false
        }
        if (!localFilesDir.exists() && !localFilesDir.mkdirs()) {
            Log.e("GakumasLocal", "Failed to recreate local-files directory: $localFilesDir")
            return false
        }
        return true
    }

    fun cleanAssets(): Boolean {
        if (!::filesDir.isInitialized) {
            Log.e("GakumasLocal", "FilesChecker.filesDir is not initialized")
            return false
        }
        return cleanAssets(filesDir)
    }
}
