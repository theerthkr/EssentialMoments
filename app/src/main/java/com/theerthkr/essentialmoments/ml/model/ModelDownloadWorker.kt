package com.theerthkr.essentialmoments.ml.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.theerthkr.essentialmoments.App
import com.theerthkr.essentialmoments.ModelDownloadActivity
import com.theerthkr.essentialmoments.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit


/**
 * Downloads model files into [ModelStore] with resume, SHA-256 verification,
 * and a foreground notification.
 *
 * Files are fetched in dependency order (vision → text → vocab) so indexing can
 * start as soon as the vision model is available, even while the large text
 * model is still downloading.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val manager = (appContext as App).modelManager
    private val store = ModelStore(appContext)
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastNotificationAt = 0L

    override suspend fun doWork(): Result {
        val rawManifest = inputData.getString(KEY_MANIFEST)
            ?: return Result.failure()
        val manifest = try {
            ModelManifest.parse(rawManifest, "")
        } catch (e: Exception) {
            Log.e(TAG, "Invalid manifest input", e)
            return Result.failure()
        }

        try {
            setForeground(createForegroundInfo("Preparing download…", indeterminate = true))
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote model download to foreground: ${e.message}")
        }

        manager.onDownloadStarted(manifest)

        val orderedFiles = listOf(ModelRole.VISION, ModelRole.TEXT, ModelRole.VOCAB)
            .mapNotNull { manifest.file(it) }

        var completedBytes = orderedFiles
            .filter { store.isFileReady(manifest, it) }
            .sumOf { it.sizeBytes }
        val overallTotal = manifest.totalBytes

        return try {
            for (spec in orderedFiles) {
                currentCoroutineContext().ensureActive()
                if (store.isFileReady(manifest, spec)) continue

                downloadFile(spec, manifest, completedBytes, overallTotal)

                val target = store.finalFile(manifest, spec)
                if (!target.exists() || target.length() != spec.sizeBytes) {
                    throw IOException("${spec.path} was not installed correctly")
                }
                manager.onFileReady(manifest, spec)
                completedBytes += spec.sizeBytes
            }

            manager.onDownloadComplete(manifest)
            updateNotification("AI models ready", indeterminate = false)
            notificationManager.cancel(NOTIFICATION_ID)
            Result.success()
        } catch (e: CancellationException) {
            manager.onDownloadCancelled()
            notificationManager.cancel(NOTIFICATION_ID)
            throw e
        } catch (e: Exception) {
            val message = e.readableMessage()
            Log.e(TAG, "Model download failed", e)
            val retryable = e is IOException && !message.contains("checksum", ignoreCase = true)
            manager.onDownloadError(message, retrying = retryable)
            updateNotification(
                if (retryable) "Download interrupted — will retry" else "Download failed",
                indeterminate = false
            )
            if (retryable) Result.retry() else Result.failure()
        }
    }

    private suspend fun downloadFile(
        spec: ModelFileSpec,
        manifest: ModelManifest,
        completedBefore: Long,
        overallTotal: Long
    ) = withContext(Dispatchers.IO) {
        val destination = store.finalFile(manifest, spec)
        val part = store.stagingFile(spec, manifest.version)
        part.parentFile?.mkdirs()
        destination.parentFile?.mkdirs()

        var existing = if (part.exists()) part.length() else 0L
        if (existing > spec.sizeBytes) {
            part.delete()
            existing = 0L
        }

        if (existing != spec.sizeBytes) {
            val requestBuilder = Request.Builder()
                .url(spec.url)
                .header("Accept-Encoding", "identity")
            if (existing > 0L) {
                requestBuilder.header("Range", "bytes=$existing-")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} while downloading ${spec.path}")
                }
                val append = existing > 0L && response.code == 206
                if (!append) existing = 0L

                val body = response.body
                    ?: throw IOException("Empty response for ${spec.path}")
                val contentLength = body.contentLength()
                val fileTotal = if (contentLength >= 0L) existing + contentLength else spec.sizeBytes
                var downloaded = existing
                var lastBytes = existing
                var lastTime = SystemClock.elapsedRealtime()

                part.parentFile?.mkdirs()
                FileOutputStream(part, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTime >= PROGRESS_INTERVAL_MS) {
                                val speed = ((downloaded - lastBytes) * 1000L) /
                                    (now - lastTime).coerceAtLeast(1L)
                                val overall = completedBefore + downloaded
                                manager.onProgress(
                                    DownloadProgress(
                                        role = spec.role,
                                        fileName = spec.path,
                                        bytes = downloaded,
                                        totalBytes = fileTotal,
                                        overallBytes = overall,
                                        overallTotalBytes = overallTotal,
                                        speedBytesPerSecond = speed
                                    )
                                )
                                updateProgressNotification(overall, overallTotal, speed)
                                lastBytes = downloaded
                                lastTime = now
                            }
                        }
                    }
                }
            }
        }

        if (part.length() != spec.sizeBytes) {
            throw IOException(
                "Incomplete download for ${spec.path}: " +
                    "${part.length()} of ${spec.sizeBytes} bytes"
            )
        }

        updateNotification("Verifying ${spec.path}…", indeterminate = true)
        val actualHash = sha256(part)
        if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("Checksum mismatch for ${spec.path}")
        }

        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        if (!part.renameTo(destination)) {
            part.copyTo(destination, overwrite = true)
            part.delete()
        }
        part.parentFile?.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createForegroundInfo(text: String, indeterminate: Boolean): ForegroundInfo {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, ModelDownloadActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getBroadcast(
            applicationContext,
            1,
            Intent(applicationContext, ModelDownloadStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading AI models")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause", pauseIntent)
            .setProgress(0, 0, indeterminate)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateProgressNotification(bytes: Long, total: Long, speed: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt < NOTIFICATION_UPDATE_INTERVAL_MS) return
        lastNotificationAt = now
        val percent = if (total > 0) ((bytes * 100) / total).toInt() else 0
        updateNotification(
            "$percent% — ${formatMb(bytes)} of ${formatMb(total)} " +
                "(${formatMb(speed)}/s)",
            indeterminate = false,
            bytes = bytes,
            total = total
        )
    }

    private fun updateNotification(
        text: String,
        indeterminate: Boolean,
        bytes: Long = 0,
        total: Long = 0
    ) {
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, ModelDownloadActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading AI models")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)

        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else if (total > 0) {
            builder.setProgress(1000, ((bytes * 1000) / total).toInt(), false)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI model downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while downloading on-device AI models"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val KEY_MANIFEST = "MODEL_MANIFEST_JSON"
        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 202
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 400L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1000L
    }
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100) "%.0f MB".format(mb) else "%.1f MB".format(mb)
}

private fun Throwable.readableMessage(): String {
    var root: Throwable = this
    var cause: Throwable? = this.cause
    while (cause != null) {
        root = cause
        cause = cause.cause
    }
    return root.message ?: this::class.java.simpleName
}
