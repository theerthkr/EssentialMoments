package com.theerthkr.essentialmoments.indexing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.theerthkr.essentialmoments.App
import com.theerthkr.essentialmoments.IndexingWorker
import com.theerthkr.essentialmoments.ml.EmbeddingModelFactory
import com.theerthkr.essentialmoments.permissions.AppPermissions

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
    internal const val KEY_BACKGROUND_INDEXING = "KEY_BACKGROUND_INDEXING"

    private const val PREFS_NAME = "indexing_prefs"
    private const val KEY_INDEXING_ENABLED = "indexing_enabled"
    private const val KEY_BACKGROUND_INDEXING_ENABLED = "background_indexing_enabled"
    private const val KEY_LAST_NUDGE_SHOWN_AT = "last_background_nudge_at"

    private const val NUDGE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L

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
     * Whether the user wants indexing to continue while the app is closed.
     *
     * For existing installs (and fresh installs before onboarding writes an
     * explicit choice) the default follows notification access so the app
     * keeps its previous behavior for users who already granted notifications.
     */
    fun isBackgroundIndexingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_BACKGROUND_INDEXING_ENABLED)) {
            prefs.getBoolean(KEY_BACKGROUND_INDEXING_ENABLED, false)
        } else {
            AppPermissions.notificationAccess(context)
        }
    }

    /**
     * Background work is only allowed when the user opted in, notifications
     * are visible, the master switch is on, and at least some photos are
     * readable.
     */
    fun isBackgroundIndexingActive(context: Context): Boolean =
        isIndexingEnabled(context) &&
            isBackgroundIndexingEnabled(context) &&
            AppPermissions.notificationAccess(context) &&
            AppPermissions.mediaAccess(context).canReadPhotos

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
            scheduleBackgroundIndexing(context)
        }
    }

    /**
     * Persists the user's background-indexing choice.
     *
     * Callers must obtain notification permission before enabling. When
     * disabling, any scheduled background work is cancelled.
     */
    fun setBackgroundIndexingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BACKGROUND_INDEXING_ENABLED, enabled).apply()
        if (enabled) {
            scheduleBackgroundIndexing(context)
        } else {
            cancel(context)
        }
    }

    /**
     * Reconciles persisted preferences with the current system permission
     * state. Call on app start and whenever the app returns to the foreground.
     */
    fun reconcile(context: Context) {
        if (isBackgroundIndexingActive(context)) {
            scheduleBackgroundIndexing(context)
        } else if (!AppPermissions.notificationAccess(context) ||
            !AppPermissions.mediaAccess(context).canReadPhotos
        ) {
            // Do not leave background work queued after the user revoked
            // notifications or photo access. A manual job (background pref off)
            // is intentionally left alone.
            cancel(context)
        }
    }

    /**
     * Enqueues a foreground worker for automatic/background indexing.
     * No-op unless background indexing is currently allowed.
     */
    fun scheduleBackgroundIndexing(context: Context) {
        if (!isBackgroundIndexingActive(context)) {
            if (!AppPermissions.notificationAccess(context) ||
                !AppPermissions.mediaAccess(context).canReadPhotos
            ) {
                cancel(context)
            }
            return
        }
        enqueueIndexAll(context, background = true)
    }

    /**
     * Enqueues indexing triggered explicitly by the user (for example the
     * Settings button). This works even when background indexing is off,
     * because the user is actively asking for it.
     */
    fun scheduleIndexNow(context: Context) {
        if (!isIndexingEnabled(context)) {
            cancel(context)
            return
        }
        if (!AppPermissions.mediaAccess(context).canReadPhotos) {
            return
        }
        enqueueIndexAll(context, background = false)
    }

    /**
     * Backward-compatible alias. New code should choose explicitly between
     * [scheduleBackgroundIndexing] and [scheduleIndexNow].
     */
    fun scheduleIndexAll(context: Context) {
        scheduleBackgroundIndexing(context)
    }

    private fun enqueueIndexAll(context: Context, background: Boolean) {
        if (!EmbeddingModelFactory.isVisionReady(context)) {
            // Models are not downloaded yet; do not enqueue work that would
            // immediately fail. The UI shows a setup prompt instead.
            return
        }
        val inputData = workDataOf(
            KEY_INDEX_ALL to true,
            KEY_BACKGROUND_INDEXING to background
        )
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
        val inputData = workDataOf(
            KEY_IMAGE_URIS to uris.toTypedArray(),
            KEY_BACKGROUND_INDEXING to false
        )
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

    /**
     * Returns true when the subtle background-indexing reminder should be
     * shown. The reminder is throttled to once a week.
     */
    fun shouldShowBackgroundIndexingNudge(context: Context): Boolean {
        if (!isIndexingEnabled(context)) return false
        if (isBackgroundIndexingActive(context)) return false
        if (!AppPermissions.mediaAccess(context).canReadPhotos) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong(KEY_LAST_NUDGE_SHOWN_AT, 0L)
        return System.currentTimeMillis() - lastShown >= NUDGE_INTERVAL_MS
    }

    fun markBackgroundIndexingNudgeShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_NUDGE_SHOWN_AT, System.currentTimeMillis())
            .apply()
    }
}
