package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.theerthkr.essentialmoments.ml.ImageEmbedder.Companion.idToContentUri

/**
 * WorkManager worker that indexes all device photos into EmbeddingStore.
 *
 * Key design decisions:
 *  - Uses content:// URIs (via idToContentUri) — never raw file paths
 *  - Idempotent: skips images already in EmbeddingStore
 *  - Saves index to disk once per BATCH (not per image) to avoid I/O thrashing
 *  - Reports progress as Data { indexed, total, failed }
 *  - Honours WorkManager cancellation via isStopped check
 */
class IndexingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "IndexingWorker"
        const val WORK_NAME   = "essential_moments_indexing"
        const val KEY_INDEXED = "indexed"
        const val KEY_TOTAL   = "total"
        const val KEY_FAILED  = "failed"
        private const val BATCH_SIZE = 16   // tune for memory vs. throughput
    }

    private val embedder = ImageEmbedder(applicationContext)
    private val store    = EmbeddingStore(applicationContext)

    override suspend fun doWork(): Result {
        return try {
            embedder.initialize(debug = false)

            if (!embedder.isInitialized) {
                Log.e(TAG, "Model failed to load — aborting indexing")
                return Result.failure()
            }

            val images = queryAllImages()
            val total  = images.size
            Log.d(TAG, "Starting indexing: $total images found, ${store.indexedCount()} already done")

            var indexed = 0
            var failed  = 0

            images.chunked(BATCH_SIZE).forEach { batch ->
                if (isStopped) {
                    Log.d(TAG, "Worker stopped — suspending at $indexed/$total")
                    return Result.retry()
                }

                batch.forEach { (imageId, _) ->
                    // Already in store? Count it and move on
                    if (store.isIndexed(imageId)) {
                        indexed++
                        return@forEach
                    }

                    // Build a proper content:// URI from the image ID
                    val uri = idToContentUri(imageId.toLong())

                    val embedding = try {
                        embedder.embed(uri)
                    } catch (e: Exception) {
                        Log.w(TAG, "embed exception for id=$imageId: ${e.message}")
                        null
                    }

                    if (embedding != null) {
                        store.store(imageId, embedding)
                        indexed++
                    } else {
                        failed++
                        Log.w(TAG, "Failed to embed imageId=$imageId  uri=$uri")
                    }
                }

                // Flush index to disk once per batch (not per image)
                store.flushIndex()

                setProgress(
                    Data.Builder()
                        .putInt(KEY_INDEXED, indexed)
                        .putInt(KEY_TOTAL,   total)
                        .putInt(KEY_FAILED,  failed)
                        .build()
                )
                Log.d(TAG, "Progress: $indexed/$total  failed=$failed")
            }

            Log.d(TAG, "✅ Indexing complete: $indexed indexed, $failed failed")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "IndexingWorker crashed: ${e.message}", e)
            Result.retry()
        } finally {
            embedder.close()
        }
    }

    /**
     * Returns list of (imageId: String, unused: String) pairs.
     * Only queries _ID — no DATA column, no file paths.
     */
    private fun queryAllImages(): List<Pair<String, String>> {
        val results    = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(MediaStore.Images.Media._ID)

        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol).toString()
                results.add(id to "")   // second slot unused — URI built on demand
            }
        }

        return results
    }
}
