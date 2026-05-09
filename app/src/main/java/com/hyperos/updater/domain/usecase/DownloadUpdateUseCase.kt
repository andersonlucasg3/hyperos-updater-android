package com.hyperos.updater.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.hyperos.updater.util.md5
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(
    val progress: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long
)

@Singleton
class DownloadUpdateUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    suspend fun download(url: String, fileName: String, expectedMd5: String? = null): Flow<DownloadProgress> = callbackFlow {
        val downloadDir = File(context.filesDir, "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val file = File(downloadDir, fileName)
        if (file.exists()) file.delete()

        val request = Request.Builder().url(url).build()
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        val body = response.body ?: throw IllegalStateException("Empty response body")
        val totalBytes = body.contentLength()

        var downloadedBytes = 0L
        var lastEmitTime = 0L
        try {
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(65536) // 64KB buffer for faster reads
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            // Emit at most every 200ms to avoid flooding the UI
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 200) {
                                val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                                trySend(
                                    DownloadProgress(
                                        progress = progress,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes
                                    )
                                )
                                lastEmitTime = now
                            }
                        }
                        // Final emission
                        val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 100
                        trySend(
                            DownloadProgress(
                                progress = progress,
                                bytesDownloaded = downloadedBytes,
                                totalBytes = totalBytes
                            )
                        )
                    }
                }
            }

            if (expectedMd5 != null) {
                val actualMd5 = file.md5()
                if (actualMd5 != null && actualMd5 != expectedMd5) {
                    file.delete()
                    throw IllegalStateException("MD5 mismatch: expected $expectedMd5, got $actualMd5")
                }
            }

            close()
        } catch (e: Exception) {
            file.delete()
            close(e)
        }

        awaitClose()
    }
}
