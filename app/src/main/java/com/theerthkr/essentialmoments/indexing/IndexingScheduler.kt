package com.theerthkr.essentialmoments.indexing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.theerthkr.essentialmoments.App
import com.theerthkr.essentialmoments.IndexingWorker
import com.theerthkr.essentialmoments.ml.EmbeddingModelFactory

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

    private const val PREFS_NAME = "indexing_prefs"
    private const val KEY_INDEXING_ENABLED = "indexing_enabled"

    /**
     * Backward-compatible property.
     */
    val ENABLED: Boolean
        get() = true

    /**
     * Returns whether photo indexing is enabled in app settings.
     * Defaults to true.
     */
    fun isIndexingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_INDEXING_ENABLED, true)
    }

    /**
     * Toggles photo indexing on or off across the app.
     *
     * - When turning off: persists false, cancels current running indexing worker,
     *   and resets the indexer state.
     * - When turning on: persists true and immediately schedules indexing for all unindexed photos.
     */
    fun setIndexingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_INDEXING_ENABLED, enabled).apply()
        if (!enabled) {
            cancel(context)
        } else {
            scheduleIndexAll(context)
        }
    }

    /**
     * Enqueues a foreground worker to index all unindexed device photos.
     * Uses KEEP policy: if a worker is already running, this is a no-op.
     */
    fun scheduleIndexAll(context: Context) {
        if (!isIndexingEnabled(context)) {
            cancel(context)
            return
        }
        if (!EmbeddingModelFactory.isVisionReady(context)) {
            // Models are not downloaded yet; do not enqueue work that would
            // immediately fail. The UI shows a setup prompt instead.
            return
        }
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
        if (!isIndexingEnabled(context)) {
            cancel(context)
            return
        }
        if (!EmbeddingModelFactory.isVisionReady(context)) {
            return
        }
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
     * Cancels any running indexing worker and resets indexer state.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        (context.applicationContext as? App)?.photoIndexer?.resetState()
    }
}
