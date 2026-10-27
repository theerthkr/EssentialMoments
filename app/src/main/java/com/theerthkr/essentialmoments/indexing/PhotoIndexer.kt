package com.theerthkr.essentialmoments.indexing

import android.net.Uri
import android.util.Log
import com.theerthkr.essentialmoments.data.VectorIndex
import com.theerthkr.essentialmoments.media.MediaCatalog
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for photo indexing state and orchestration.
 *
 * Deep module: all indexing pipeline steps—URI discovery, deduplication,
 * batched embedding, and persistence—hide behind [indexAll] and [indexUris].
 * Callers observe one [state] flow for progress and never touch MediaStore,
 * ObjectBox, or WorkManager keys directly.
 */
sealed class IndexingState {
    data object Idle : IndexingState()
    data class Indexing(val current: Int, val total: Int) : IndexingState()
    data class Done(val count: Int) : IndexingState()
    data class Error(val message: String) : IndexingState()
}

class PhotoIndexer(
    private val mediaCatalog: MediaCatalog,
    private val vectorIndex: VectorIndex,
    private val imageEmbedderFactory: () -> ImageEmbedder,
    private val batchSize: Int = 10
) {
    private val TAG = "PhotoIndexer"

    private fun logD(msg: String) {
        try { Log.d(TAG, msg) } catch (_: Throwable) { println("[$TAG] $msg") }
    }

    private fun logW(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) { println("[$TAG] WARN: $msg") }
    }

    private fun logE(msg: String, tr: Throwable? = null) {
        try { Log.e(TAG, msg, tr) } catch (_: Throwable) { println("[$TAG] ERROR: $msg ${tr?.message}") }
    }

    private val _state = MutableStateFlow<IndexingState>(IndexingState.Idle)

    /** Single source of truth for indexing progress. Observed by Workers and ViewModels. */
    val state: StateFlow<IndexingState> = _state.asStateFlow()

    /** Prevents concurrent indexing. Second caller suspends until the first finishes. */
    private val indexingMutex = Mutex()

    /**
     * Indexes all unindexed photos on the device.
     *
     * Suspends until complete. Safe to call from CoroutineWorker.doWork() or
     * viewModelScope.launch{}. The [indexingMutex] prevents concurrent runs.
     */
    suspend fun indexAll() {
        indexingMutex.withLock {
            try {
                _state.value = IndexingState.Indexing(0, 0)
                val allUris = withContext(Dispatchers.IO) {
                    mediaCatalog.allPhotoUris()
                }
                indexUrisInternal(allUris)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = IndexingState.Idle
                throw e
            } catch (e: Exception) {
                logE("indexAll failed", e)
                _state.value = IndexingState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Indexes a specific list of photo URIs (e.g. from a picker).
     *
     * URIs are normalized to canonical content:// format via [MediaCatalog.normalizeUri].
     */
    suspend fun indexUris(uris: List<String>) {
        indexingMutex.withLock {
            try {
                _state.value = IndexingState.Indexing(0, uris.size)
                // Normalize picker/file URIs to canonical MediaStore URIs
                val normalized = uris.mapNotNull { mediaCatalog.normalizeUri(it) }
                indexUrisInternal(normalized)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = IndexingState.Idle
                throw e
            } catch (e: Exception) {
                logE("indexUris failed", e)
                _state.value = IndexingState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Shared pipeline: filter → embed → persist in batches → emit progress.
     *
     * All embeddings are tagged with [embedder.modelId] so a photo embedded by
     * model A is never compared against an embedding from model B.
     */
    private suspend fun indexUrisInternal(uris: List<String>) {
        imageEmbedderFactory().use { embedder ->
            val modelId = embedder.modelId

            // Batch-filter already indexed URIs (single set-lookup, not N+1) for this modelId
            val unindexed = withContext(Dispatchers.IO) {
                vectorIndex.filterUnindexed(uris, modelId)
            }
            val total = unindexed.size
            if (total == 0) {
                _state.value = IndexingState.Done(0)
                return
            }
            _state.value = IndexingState.Indexing(0, total)
            logD("Starting indexing: $total unindexed photos (modelId=$modelId)")

            val batch = mutableListOf<VectorIndex.IndexEntry>()
            var processed = 0

            for (uriString in unindexed) {
                // Cooperative cancellation: respects Worker.isStopped → coroutine cancellation
                currentCoroutineContext().ensureActive()

                val embedding = try {
                    embedder.getEmbedding(uriString)
                } catch (e: Exception) {
                    logW("Skipping $uriString: ${e.message}")
                    null
                }

                if (embedding != null) {
                    batch.add(VectorIndex.IndexEntry(uriString, embedding, modelId))
                }

                // Flush batch to ObjectBox
                if (batch.size >= batchSize) {
                    withContext(Dispatchers.IO) { vectorIndex.putBatch(batch.toList()) }
                    batch.clear()
                }

                processed++
                _state.value = IndexingState.Indexing(processed, total)
            }

            // Flush remaining entries
            if (batch.isNotEmpty()) {
                withContext(Dispatchers.IO) { vectorIndex.putBatch(batch.toList()) }
            }

            logD("Indexing complete: $total photos processed (modelId=$modelId)")
            _state.value = IndexingState.Done(total)
        }
    }
}
