package com.theerthkr.essentialmoments.indexing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.theerthkr.essentialmoments.IndexingWorker

/**
 * Single place that knows the WorkManager unique-work key and input data format.
 *
 * No other file should reference "image_indexing_task", KEY_INDEX_ALL, or
 * KEY_IMAGE_URIS directly. This eliminates the scattered raw-string keys
 * that previously leaked across SearchViewModel, SearchActivity, ModelActivity,
 * and MainActivity.
 */
object IndexingScheduler {

    internal const val UNIQUE_WORK_NAME = "image_indexing_task"
    internal const val KEY_INDEX_ALL = "KEY_INDEX_ALL"
    internal const val KEY_IMAGE_URIS = "KEY_IMAGE_URIS"

    /**
     * Enqueues a foreground worker to index all unindexed device photos.
     * Uses KEEP policy: if a worker is already running, this is a no-op.
     */
    fun scheduleIndexAll(context: Context) {
        val inputData = workDataOf(KEY_INDEX_ALL to true)
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Enqueues a foreground worker to index specific photo URIs.
     * Uses REPLACE policy: restarts with the new selection.
     */
    fun scheduleIndexUris(context: Context, uris: List<String>) {
        val inputData = workDataOf(KEY_IMAGE_URIS to uris.toTypedArray())
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Cancels any running indexing worker.
     * Should only be called from StopIndexingReceiver (notification action).
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
