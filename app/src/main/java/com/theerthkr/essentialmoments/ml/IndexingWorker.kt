package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import androidx.work.*
import com.theerthkr.essentialmoments.ml.ImageEmbedder.Companion.idToContentUri

/**
 * Resumable indexing worker.
 *
 * WHY IT WAS BEING CANCELLED:
 *   WorkManager enforces a hard 10-minute execution limit per worker run.
 *   4,178 images × ~1-2s each = 1-2 hours → cancelled after 10 minutes every time.
 *
 * FIX — two-part approach:
 *   1. Each run processes at most MAX_IMAGES_PER_RUN images then exits cleanly.
 *   2. If work remains, it re-enqueues itself with REPLACE policy so the
 *      completed work record is replaced and the next batch actually starts.
 *   3. EmbeddingStore.isIndexed() ensures already-done images are skipped,
 *      so re-runs are free — they just fast-forward past completed work.
 *
 * WHY IT WAS STOPPING AT 200 IMAGES AND NEVER RESTARTING:
 *   The original enqueue() used ExistingWorkPolicy.KEEP. When the worker
 *   re-enqueued itself after completing 200 images, WorkManager found the
 *   existing (now-succeeded) work record and silently did nothing — KEEP
 *   means "if any record exists, keep it and ignore this request." Indexing
 *   stopped permanently after the first batch every time.
 *
 *   Fix: the internal re-enqueue uses REPLACE so the completed record is
 *   cleared and the next batch is actually scheduled. The public enqueue()
 *   still uses KEEP so the UI can't accidentally start a duplicate mid-run.
 *
 * Progress across runs:
 *   totalIndexed = store.indexedCount() — persisted on disk between runs.
 *   The UI reads this from SearchViewModel which polls indexedCount().
 */
class IndexingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG                = "IndexingWorker"
        const val WORK_NAME                  = "essential_moments_indexing"
        const val KEY_INDEXED                = "indexed"
        const val KEY_TOTAL                  = "total"
        const val KEY_FAILED                 = "failed"
        private const val BATCH_SIZE         = 16
        // Stay well within the 10-min WorkManager limit.
        // At ~1-2s/image on GPU+CPU: 200 images ≈ 3-6 minutes per run.
        private const val MAX_IMAGES_PER_RUN = 200

        /**
         * Called from UI — safe to call multiple times.
         * KEEP means "don't start a duplicate if already running."
         */
        fun enqueue(context: Context) {
            enqueueWithPolicy(context, ExistingWorkPolicy.KEEP)
        }

        /**
         * Called internally at the end of each run to chain the next batch.
         * REPLACE clears the completed work record so the next run actually starts.
         * Without this, KEEP would silently drop the re-enqueue and indexing
         * would stop permanently after the first 200 images.
         */
        private fun reenqueue(context: Context) {
            enqueueWithPolicy(context, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueueWithPolicy(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<IndexingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }

    private val embedder = ImageEmbedder(applicationContext, workerMode = true)
    private val store    = EmbeddingStore(applicationContext)

    override suspend fun doWork(): Result {
        return try {
            embedder.initialize(debug = false)
            if (!embedder.isInitialized) {
                Log.e(TAG, "Model failed to load")
                return Result.failure()
            }
            Log.d(TAG, "Model: ${embedder.activeDelegate}")

            val allImages   = queryAllImages()
            val total       = allImages.size
            val alreadyDone = store.indexedCount()
            Log.d(TAG, "Total: $total  already done: $alreadyDone")

            // Skip already-indexed images, take only our per-run budget
            val pending   = allImages.filter { !store.isIndexed(it) }
            val toProcess = pending.take(MAX_IMAGES_PER_RUN)

            Log.d(TAG, "This run: processing ${toProcess.size} of ${pending.size} pending images")

            var indexed = 0
            var failed  = 0

            toProcess.chunked(BATCH_SIZE).forEach { batch ->
                if (isStopped) {
                    Log.d(TAG, "Stopped — will resume next run")
                    store.flushIndex()
                    return Result.retry()
                }

                batch.forEach { imageId ->
                    val embedding = embedAndRecycle(imageId)
                    if (embedding != null) {
                        store.store(imageId, embedding)
                        indexed++
                    } else {
                        failed++
                        Log.w(TAG, "Failed id=$imageId")
                    }
                }

                store.flushIndex()

                val totalDone = alreadyDone + indexed
                setProgress(
                    Data.Builder()
                        .putInt(KEY_INDEXED, totalDone)
                        .putInt(KEY_TOTAL,   total)
                        .putInt(KEY_FAILED,  failed)
                        .build()
                )
                Log.d(TAG, "Batch done: $totalDone/$total  failed=$failed")
            }

            store.flushIndex()
            Log.d(TAG, "Run complete: +$indexed indexed, $failed failed  " +
                    "total=${store.indexedCount()}/$total")

            // If there are still unindexed images, re-enqueue for the next batch.
            // Uses REPLACE (not KEEP) so the completed work record is cleared
            // and WorkManager actually schedules the next run.
            val remaining = total - store.indexedCount()
            if (remaining > 0) {
                Log.d(TAG, "$remaining images remaining — re-enqueueing next batch")
                reenqueue(applicationContext)
            } else {
                Log.d(TAG, "✅ All $total images indexed!")
            }

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Worker crashed: ${e.message}", e)
            Result.retry()
        } finally {
            embedder.close()
        }
    }

    private fun embedAndRecycle(imageId: String): FloatArray? {
        var bmp: Bitmap? = null
        return try {
            val uri = idToContentUri(imageId.toLong())

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            applicationContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize       = calcSampleSize(bounds.outWidth, bounds.outHeight, 448)
                inJustDecodeBounds = false
                inPreferredConfig  = Bitmap.Config.ARGB_8888
            }
            bmp = applicationContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            embedder.embed(bmp, debug = false)
        } catch (e: Exception) {
            Log.w(TAG, "embed failed id=$imageId: ${e.message}")
            null
        } finally {
            bmp?.recycle()
        }
    }

    private fun calcSampleSize(w: Int, h: Int, targetPx: Int): Int {
        var s = 1
        while (minOf(w, h) / (s * 2) >= targetPx) s *= 2
        return s
    }

    private fun queryAllImages(): List<String> {
        val out = mutableListOf<String>()
        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) out.add(c.getLong(col).toString())
        }
        return out
    }
}
