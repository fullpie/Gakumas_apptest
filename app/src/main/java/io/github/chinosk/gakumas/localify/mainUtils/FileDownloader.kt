package io.github.chinosk.gakumas.localify.mainUtils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import okhttp3.*
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object FileDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var call: Call? = null
    @Volatile
    private var downloadManager: DownloadManager? = null
    @Volatile
    private var downloadManagerId: Long? = null

    fun requestGet(request: Request, callback: Callback) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(request)
        call.enqueue(callback)
    }

    fun downloadFile(
        url: String,
        onDownload: (Float, downloaded: Long, size: Long) -> Unit,
        onSuccess: (ByteArray) -> Unit,
        onFailed: (Int, String) -> Unit,
        checkContentTypes: List<String>? = null
    ) {
        try {
            if (call != null) {
                onFailed(-1, "Another file is downloading.")
                return
            }
            val request = Request.Builder()
                .url(url)
                .build()

            call = client.newCall(request)
            call?.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    this@FileDownloader.call = null
                    if (call.isCanceled()) {
                        onFailed(-1, "Download canceled")
                    } else {
                        onFailed(-1, e.message ?: "Unknown error")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        this@FileDownloader.call = null
                        onFailed(response.code, response.message)
                        return
                    }

                    if (checkContentTypes != null) {
                        val contentType = response.header("Content-Type")
                        if (!checkContentTypes.contains(contentType)) {
                            onFailed(-1, "Unexpected content type: $contentType")
                            this@FileDownloader.call = null
                            return
                        }
                    }

                    response.body?.let { responseBody ->
                        val contentLength = responseBody.contentLength()
                        val inputStream = responseBody.byteStream()
                        val buffer = ByteArray(8 * 1024)
                        var downloadedBytes = 0L
                        var read: Int
                        val outputStream = ByteArrayOutputStream()

                        try {
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                                downloadedBytes += read
                                val progress = if (contentLength < 0) {
                                    0f
                                }
                                else {
                                    downloadedBytes.toFloat() / contentLength
                                }
                                onDownload(progress, downloadedBytes, contentLength)
                            }
                            onSuccess(outputStream.toByteArray())
                        } catch (e: IOException) {
                            if (call.isCanceled()) {
                                onFailed(-1, "Download canceled")
                            } else {
                                onFailed(-1, e.message ?: "Error reading stream")
                            }
                        } finally {
                            this@FileDownloader.call = null
                            inputStream.close()
                            outputStream.close()
                        }
                    } ?: run {
                        this@FileDownloader.call = null
                        onFailed(-1, "Response body is null")
                    }
                }
            })
        }
        catch (e: Exception) {
            onFailed(-1, e.toString())
            call = null
        }

    }

    fun downloadFileTo(
        url: String,
        outputFile: File,
        onDownload: (Float, downloaded: Long, size: Long) -> Unit,
        onSuccess: (File) -> Unit,
        onFailed: (Int, String) -> Unit,
        checkContentTypes: List<String>? = null
    ) {
        try {
            if (call != null) {
                onFailed(-1, "Another file is downloading.")
                return
            }
            val request = Request.Builder()
                .url(url)
                .build()

            call = client.newCall(request)
            call?.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    this@FileDownloader.call = null
                    if (call.isCanceled()) {
                        onFailed(-1, "Download canceled")
                    } else {
                        onFailed(-1, e.message ?: "Unknown error")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        this@FileDownloader.call = null
                        onFailed(response.code, response.message)
                        return
                    }

                    if (checkContentTypes != null) {
                        val contentType = response.header("Content-Type")?.substringBefore(";")
                        if (contentType != null && !checkContentTypes.contains(contentType)) {
                            onFailed(-1, "Unexpected content type: $contentType")
                            this@FileDownloader.call = null
                            return
                        }
                    }

                    response.body?.let { responseBody ->
                        outputFile.parentFile?.mkdirs()
                        val tempFile = File(outputFile.parentFile, "${outputFile.name}.download")
                        val contentLength = responseBody.contentLength()
                        val inputStream = responseBody.byteStream()
                        val buffer = ByteArray(128 * 1024)
                        var downloadedBytes = 0L
                        var read: Int

                        try {
                            FileOutputStream(tempFile).use { outputStream ->
                                while (inputStream.read(buffer).also { read = it } != -1) {
                                    outputStream.write(buffer, 0, read)
                                    downloadedBytes += read
                                    val progress = if (contentLength < 0) {
                                        0f
                                    }
                                    else {
                                        downloadedBytes.toFloat() / contentLength
                                    }
                                    onDownload(progress, downloadedBytes, contentLength)
                                }
                            }
                            if (outputFile.exists()) outputFile.delete()
                            if (!tempFile.renameTo(outputFile)) {
                                tempFile.copyTo(outputFile, overwrite = true)
                                tempFile.delete()
                            }
                            onSuccess(outputFile)
                        } catch (e: IOException) {
                            tempFile.delete()
                            if (call.isCanceled()) {
                                onFailed(-1, "Download canceled")
                            } else {
                                onFailed(-1, e.message ?: "Error reading stream")
                            }
                        } finally {
                            this@FileDownloader.call = null
                            inputStream.close()
                        }
                    } ?: run {
                        this@FileDownloader.call = null
                        onFailed(-1, "Response body is null")
                    }
                }
            })
        }
        catch (e: Exception) {
            onFailed(-1, e.toString())
            call = null
        }
    }

    fun downloadFileWithSystemManager(
        context: Context,
        url: String,
        outputFile: File,
        title: String,
        description: String,
        onDownload: (Float, downloaded: Long, size: Long) -> Unit,
        onSuccess: (File) -> Unit,
        onFailed: (Int, String) -> Unit
    ) {
        try {
            if (call != null || downloadManagerId != null) {
                onFailed(-1, "Another file is downloading.")
                return
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription(description)
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(outputFile))

            val id = manager.enqueue(request)
            downloadManager = manager
            downloadManagerId = id

            thread(name = "system-download-progress") {
                val query = DownloadManager.Query().setFilterById(id)
                while (downloadManagerId == id) {
                    manager.query(query)?.use { cursor ->
                        if (!cursor.moveToFirst()) {
                            downloadManagerId = null
                            downloadManager = null
                            onFailed(-1, "Download no longer exists.")
                            return@thread
                        }

                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val progress = if (size > 0) downloaded.toFloat() / size else 0f
                        onDownload(progress, downloaded, size)

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                downloadManagerId = null
                                downloadManager = null
                                onDownload(1f, outputFile.length(), outputFile.length())
                                onSuccess(outputFile)
                                return@thread
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                downloadManagerId = null
                                downloadManager = null
                                outputFile.delete()
                                onFailed(reason, "System download failed: $reason")
                                return@thread
                            }
                        }
                    }
                    Thread.sleep(700)
                }
            }
        }
        catch (e: Exception) {
            downloadManagerId = null
            downloadManager = null
            outputFile.delete()
            onFailed(-1, e.message ?: e.toString())
        }
    }

    fun cancel() {
        call?.cancel()
        this@FileDownloader.call = null
        downloadManagerId?.let { id ->
            downloadManager?.remove(id)
        }
        downloadManagerId = null
        downloadManager = null
    }

    /**
    * return: Status, newString
    * Status: 0 - not change, 1 - need check, 2 - modified, 3 - checked
    **/
    fun checkAndChangeDownloadURL(url: String, forceEdit: Boolean = false): Pair<Int, String> {

        if (!url.startsWith("https://github.com/")) {  // check github only
            return Pair(0, url)
        }
        if (url.endsWith(".zip")) {
            return Pair(0, url)
        }

        // https://github.com/chinosk6/GakumasTranslationData
        // https://github.com/chinosk6/GakumasTranslationData.git
        // https://github.com/chinosk6/GakumasTranslationData/archive/refs/heads/main.zip
        if (url.endsWith(".git")) {
            return Pair(2, "${url.substring(0, url.length - 4)}/archive/refs/heads/main.zip")
        }

        if (forceEdit) {
            return Pair(3, "$url/archive/refs/heads/main.zip")
        }

        return Pair(1, url)
    }
}
