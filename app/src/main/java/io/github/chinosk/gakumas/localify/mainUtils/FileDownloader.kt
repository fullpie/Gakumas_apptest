package io.github.chinosk.gakumas.localify.mainUtils

import okhttp3.*
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
    private var cancelRequested: Boolean = false

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

    fun downloadFileResumable(
        url: String,
        outputFile: File,
        expectedSha256: String? = null,
        onDownload: (Float, downloaded: Long, size: Long) -> Unit,
        onSuccess: (File) -> Unit,
        onFailed: (Int, String) -> Unit
    ) {
        try {
            if (call != null) {
                onFailed(-1, "Another file is downloading.")
                return
            }

            outputFile.parentFile?.mkdirs()
            cancelRequested = false

            thread(name = "resumable-apk-download") {
                val partialFile = File(outputFile.parentFile, "${outputFile.name}.download")
                val partialMetaFile = File(outputFile.parentFile, "${outputFile.name}.download.meta")
                try {
                    if (outputFile.exists()) {
                        if (expectedSha256 == null || fileSha256(outputFile).equals(expectedSha256, ignoreCase = true)) {
                            onDownload(1f, outputFile.length(), outputFile.length())
                            onSuccess(outputFile)
                            return@thread
                        }
                        outputFile.delete()
                    }
                    if (expectedSha256 != null) {
                        val previousExpected = partialMetaFile.takeIf { it.exists() }?.readText()?.trim()
                        if (partialFile.exists() && previousExpected != null &&
                            !previousExpected.equals(expectedSha256, ignoreCase = true)
                        ) {
                            partialFile.delete()
                        }
                        partialMetaFile.writeText(expectedSha256)
                    }

                    var attempts = 0
                    while (!cancelRequested) {
                        try {
                            downloadAttempt(url, partialFile, onDownload)
                            if (cancelRequested) break

                            if (expectedSha256 != null &&
                                !fileSha256(partialFile).equals(expectedSha256, ignoreCase = true)
                            ) {
                                partialFile.delete()
                                onFailed(-1, "Downloaded file checksum mismatch.")
                                return@thread
                            }

                            if (outputFile.exists()) outputFile.delete()
                            if (!partialFile.renameTo(outputFile)) {
                                partialFile.copyTo(outputFile, overwrite = true)
                                partialFile.delete()
                            }
                            partialMetaFile.delete()
                            onDownload(1f, outputFile.length(), outputFile.length())
                            onSuccess(outputFile)
                            return@thread
                        } catch (e: IOException) {
                            if (cancelRequested) break
                            attempts += 1
                            if (attempts >= 5) {
                                onFailed(-1, "Download paused: ${e.message ?: "network error"}. Press download again to resume.")
                                return@thread
                            }
                            Thread.sleep((attempts * 1500L).coerceAtMost(6000L))
                        }
                    }
                    onFailed(-1, "Download canceled")
                } catch (e: Exception) {
                    onFailed(-1, e.message ?: e.toString())
                } finally {
                    call = null
                }
            }
        }
        catch (e: Exception) {
            onFailed(-1, e.message ?: e.toString())
        }
    }

    private fun downloadAttempt(
        url: String,
        partialFile: File,
        onDownload: (Float, downloaded: Long, size: Long) -> Unit
    ) {
        partialFile.parentFile?.mkdirs()
        var existingBytes = if (partialFile.exists()) partialFile.length() else 0L
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val activeCall = client.newCall(requestBuilder.build())
        call = activeCall
        activeCall.execute().use { response ->
            if (response.code == 416) {
                partialFile.delete()
                throw IOException("Server rejected resume offset; restarting download.")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            val append = existingBytes > 0 && response.code == 206
            if (existingBytes > 0 && !append) {
                partialFile.delete()
                existingBytes = 0L
            }

            val body = response.body ?: throw IOException("Response body is null")
            val totalSize = totalSizeFromResponse(response, existingBytes, body.contentLength())
            var downloadedBytes = existingBytes
            body.byteStream().use { input ->
                FileOutputStream(partialFile, append).use { output ->
                    val buffer = ByteArray(512 * 1024)
                    while (true) {
                        if (cancelRequested) throw IOException("Download canceled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val progress = if (totalSize > 0) downloadedBytes.toFloat() / totalSize else 0f
                        onDownload(progress.coerceIn(0f, 1f), downloadedBytes, totalSize)
                    }
                }
            }
        }
    }

    private fun totalSizeFromResponse(response: Response, existingBytes: Long, contentLength: Long): Long {
        val rangeTotal = response.header("Content-Range")
            ?.substringAfter("/", missingDelimiterValue = "")
            ?.toLongOrNull()
        if (rangeTotal != null && rangeTotal > 0) return rangeTotal
        return if (contentLength >= 0) existingBytes + contentLength else -1
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun cancel() {
        cancelRequested = true
        call?.cancel()
        this@FileDownloader.call = null
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
