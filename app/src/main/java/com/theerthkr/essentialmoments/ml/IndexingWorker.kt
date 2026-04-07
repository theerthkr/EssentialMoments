package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters

/**
 * WorkManager worker that indexes all device photos.
 * Reports progress as:  Data { "indexed": Int, "total": Int, "failed": Int }
 *
 * Idempotent — skips images already in EmbeddingStore.
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
        private const val BATCH_SIZE = 20
    }

    private val embedder = ImageEmbedder(applicationContext)
    private val store    = EmbeddingStore(applicationContext)

    override suspend fun doWork(): Result {
        return try {
            embedder.initialize(debug = false)
            val images = queryAllImages()
            Log.d(TAG, "Starting indexing: ${images.size} images, ${store.indexedCount()} already done")

            var indexed = 0
            var failed  = 0
            val total   = images.size

            images.chunked(BATCH_SIZE).forEach { batch ->
                if (isStopped) return Result.retry()  // honour cancellation

                batch.forEach { (imageId, uri) ->
                    if (store.isIndexed(imageId)) {
                        indexed++
                        return@forEach
                    }

                    val embedding = embedder.embed(Uri.parse(uri))
                    if (embedding != null) {
                        store.store(imageId, embedding)
                        indexed++
                    } else {
                        failed++
                        Log.w(TAG, "Failed to embed imageId=$imageId")
                    }
                }

                // Report progress after each batch
                setProgress(
                    Data.Builder()
                        .putInt(KEY_INDEXED, indexed)
                        .putInt(KEY_TOTAL,   total)
                        .putInt(KEY_FAILED,  failed)
                        .build()
                )
                Log.d(TAG, "Progress: $indexed/$total  failed=$failed")
            }

            Log.d(TAG, "Indexing complete: $indexed indexed, $failed failed")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "IndexingWorker failed: ${e.message}", e)
            Result.retry()
        } finally {
            embedder.close()
        }
    }

    private fun queryAllImages(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA
        )
        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val id  = cursor.getLong(idCol).toString()
                val uri = cursor.getString(dataCol)
                results.add(id to uri)
            }
        }
        return results
    }
}