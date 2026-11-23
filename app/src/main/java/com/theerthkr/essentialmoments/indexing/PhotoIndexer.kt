package com.theerthkr.essentialmoments.indexing

import android.util.Log
import com.theerthkr.essentialmoments.concurrency.BackgroundDispatchers
import com.theerthkr.essentialmoments.data.VectorIndex
import com.theerthkr.essentialmoments.media.MediaCatalog
import com.theerthkr.essentialmoments.media.PhotoCandidate
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
 * Deep module: all indexing pipeline steps—catalog reconciliation, deduplication,
 * batched embedding, and persistence—hide behind [indexAll] and [indexUris].
 * Callers observe one [state] flow for progress and never touch MediaStore,
 * the database, or WorkManager keys directly.
 */
sealed class IndexingState {
    data object Idle : IndexingState()
    data class Indexing(val processed: Int, val pending: Int) : IndexingState()

    /**
     * [indexed] counts rows written in this run. [skipped] counts inputs that
     * could not be read; they are retried on the next scan.
     */
    data class Done(val indexed: Int, val skipped: Int = 0) : IndexingState()
    data class Error(val message: String) : IndexingState()
}

class PhotoIndexer(
    private val mediaCatalog: MediaCatalog,
    private val vectorIndex: VectorIndex,
    /**
     * Model id used for reconciliation. Kept separate from
     * [imageEmbedderFactory] so an unchanged scan can prune rows without
     * loading the ONNX session.
     */
    private val imageModelId: () -> String,
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

    /** Resets the state back to Idle (e.g. when cancelled or stopped). */
    fun resetState() {
        _state.value = IndexingState.Idle
    }

    /** Prevents concurrent indexing. Second caller suspends until the first finishes. */
    private val indexingMutex = Mutex()

    /**
     * Indexes every readable photo and removes rows that are no longer in
     * MediaStore or whose content changed.
     *
     * Suspends until complete. Safe to call from CoroutineWorker.doWork() or
     * viewModelScope.launch{}. The [indexingMutex] prevents concurrent runs.
     */
    suspend fun indexAll() {
        indexingMutex.withLock {
            try {
                _state.value = IndexingState.Indexing(0, 0)
                val candidates = withContext(Dispatchers.IO) {
                    mediaCatalog.photoCandidates()
                }
                val pending = reconcile(candidates, pruneMissing = true)
                embed(pending)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = IndexingState.Idle
                throw e
            } catch (e: Exception) {
                logE("indexAll failed", e)
                _state.value = IndexingState.Error(e.message ?: "Unknown error")
                throw e
            }
        }
    }

    /**
     * Indexes a specific list of photo URIs (e.g. from a picker or a MediaStore
     * change notification).
     *
     * URIs are normalized to canonical content:// format via [MediaCatalog.normalizeUri].
     * With [force] the existing embeddings are deleted before re-embedding, which
     * is what a content change or an explicit re-pick requires.
     */
    suspend fun indexUris(uris: List<String>, force: Boolean = true) {
        indexingMutex.withLock {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    uris.map { raw ->
                        val normalized = mediaCatalog.normalizeUri(raw) ?: raw
                        normalized to mediaCatalog.photoCandidate(normalized)
                    }
                }
                val candidates = resolved.mapNotNull { it.second }
                val unreadableUris = resolved.filter { it.second == null }
                    .map { it.first }
                    .toSet()

                val forceUris = if (force) {
                    candidates.mapTo(unreadableUris.toHashSet()) { it.uri }
                } else {
                    unreadableUris
                }

                val pending = reconcile(
                    candidates = candidates,
                    pruneMissing = false,
                    forceUris = forceUris
                )
                embed(pending)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = IndexingState.Idle
                throw e
            } catch (e: Exception) {
                logE("indexUris failed", e)
                _state.value = IndexingState.Error(e.message ?: "Unknown error")
                throw e
            }
        }
    }

    /**
     * Deletes rows for photos that disappeared or changed, without embedding
     * anything. Used by the MediaStore observer so deletion is immediate and
     * re-embedding can happen later through the normal indexing pipeline.
     */
    suspend fun reconcileNow() {
        indexingMutex.withLock {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    mediaCatalog.photoCandidates()
                }
                val modelId = imageModelId()
                val pending = withContext(Dispatchers.IO) {
                    vectorIndex.reconcile(candidates, modelId, pruneMissing = true)
                }
                if (pending.isNotEmpty()) {
                    logD("Reconcile marked ${pending.size} photo(s) for re-indexing (modelId=$modelId)")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("reconcileNow failed", e)
            }
        }
    }

    /** Removes every embedding, e.g. when all photo access is revoked. */
    suspend fun clearIndex() {
        indexingMutex.withLock {
            withContext(Dispatchers.IO) { vectorIndex.clear() }
            _state.value = IndexingState.Idle
        }
    }

    private suspend fun reconcile(
        candidates: List<PhotoCandidate>,
        pruneMissing: Boolean,
        forceUris: Set<String> = emptySet()
    ): List<PhotoCandidate> = withContext(Dispatchers.IO) {
        val modelId = imageModelId()
        vectorIndex.reconcile(candidates, modelId, pruneMissing, forceUris)
    }

    /**
     * Embeds and persists [pending] candidates. The embedder is only created
     * when there is work, so an unchanged scan never loads the ONNX session.
     */
    private suspend fun embed(pending: List<PhotoCandidate>) {
        val total = pending.size
        if (total == 0) {
            _state.value = IndexingState.Done(0, 0)
            return
        }

        _state.value = IndexingState.Indexing(0, total)
        logD("Starting indexing: $total pending photos")

        var processed = 0
        var indexed = 0
        val batch = mutableListOf<VectorIndex.IndexEntry>()

        imageEmbedderFactory().use { embedder ->
            for (candidate in pending) {
                // Cooperative cancellation: respects Worker.isStopped → coroutine cancellation
                currentCoroutineContext().ensureActive()

                val embedding = try {
                    // Decode + inference are CPU-bound; keep them on one low-priority
                    // worker so indexing never starves the UI thread during scrolling.
                    withContext(BackgroundDispatchers.indexing) {
                        embedder.getEmbedding(candidate.uri)
                    }
                } catch (e: Exception) {
                    logW("Skipping ${candidate.uri}: ${e.message}")
                    null
                }

                if (embedding != null && embedding.isNotEmpty()) {
                    batch.add(
                        VectorIndex.IndexEntry(
                            uri = candidate.uri,
                            embedding = embedding,
                            modelId = embedder.modelId,
                            fingerprint = candidate.fingerprint
                        )
                    )
                    indexed++
                }

                // Flush batch to the vector index
                if (batch.size >= batchSize) {
                    withContext(Dispatchers.IO) { vectorIndex.putBatch(batch.toList()) }
                    batch.clear()
                }

                processed++
                _state.value = IndexingState.Indexing(processed, total)
            }
        }

        if (batch.isNotEmpty()) {
            withContext(Dispatchers.IO) { vectorIndex.putBatch(batch.toList()) }
        }

        val skipped = total - indexed
        logD("Indexing complete: $indexed stored, $skipped skipped of $total")
        _state.value = IndexingState.Done(indexed, skipped)
    }
}
