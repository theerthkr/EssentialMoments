package com.theerthkr.essentialmoments

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theerthkr.essentialmoments.indexing.IndexingScheduler
import com.theerthkr.essentialmoments.indexing.IndexingState
import kotlinx.coroutines.launch

/**
 * Thin Android WorkManager adapter for background photo indexing.
 *
 * All indexing logic lives inside [PhotoIndexer] (the deep module).
 * This adapter's only responsibilities are:
 * 1. Promoting to a foreground service with notification
 * 2. Delegating to [PhotoIndexer.indexAll] or [PhotoIndexer.indexUris]
 * 3. Observing [PhotoIndexer.state] to update notification progress and [setProgress]
 */
class IndexingWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "IndexingWorker"
    private val notificationId = 101
    private val channelId = "indexing_channel"

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())
        Log.d(TAG, "Worker promoted to Foreground Service.")

        val app = applicationContext as App
        val indexer = app.photoIndexer

        val indexAll = inputData.getBoolean(IndexingScheduler.KEY_INDEX_ALL, false)
        val providedUris = inputData.getStringArray(IndexingScheduler.KEY_IMAGE_URIS)?.toList()
            ?: emptyList()

        return kotlinx.coroutines.coroutineScope {
            val observerJob = launch {
                indexer.state.collect { state ->
                    when (state) {
                        is IndexingState.Indexing -> {
                            setProgress(
                                workDataOf(
                                    "PROGRESS" to state.current,
                                    "TOTAL" to state.total
                                )
                            )
                            updateNotification(state.current, state.total)
                        }
                        else -> { /* Idle, Done, Error — handled by return value */ }
                    }
                }
            }

            try {
                if (indexAll) {
                    indexer.indexAll()
                } else {
                    indexer.indexUris(providedUris)
                }
                observerJob.cancel()
                Log.d(TAG, "Indexing complete.")
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                observerJob.cancel()
                Log.w(TAG, "Worker cancelled.")
                Result.success() // Don't retry on explicit cancellation
            } catch (e: Exception) {
                observerJob.cancel()
                Log.e(TAG, "Worker error: ${e.message}", e)
                Result.retry()
            }
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val stopIntent = Intent(applicationContext, StopIndexingReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Image Indexing", NotificationManager.IMPORTANCE_LOW
            )
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Indexing in progress")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(current: Int, total: Int) {
        val stopIntent = Intent(applicationContext, StopIndexingReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Indexing in progress")
            .setContentText("Processing image $current of $total")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent)
            .setProgress(total, current, false)
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.notify(notificationId, notification)
    }
}
