package com.theerthkr.essentialmoments.data

import com.theerthkr.essentialmoments.media.PhotoCandidate

/**
 * Deep module interface for vector storage, deduplication, and similarity search.
 *
 * Hides the storage engine (Room), reconciliation logic, and ranking math behind a
 * small interface. Callers never see [ImageIndexEntity], the DAO, or raw database
 * cursors.
 *
 * All operations are suspend functions: Room must not be queried on the main
 * thread, and callers already invoke the index from coroutines.
 */
interface VectorIndex {

    data class IndexEntry(
        val uri: String,
        val embedding: FloatArray,
        val modelId: String,
        val fingerprint: String
    )
    data class ScoredResult(val uri: String, val score: Float)

    /**
     * Reconciles stored rows against the current MediaStore catalog for [modelId].
     *
     * Rows are deleted when:
     * - their URI is absent from [candidates] and [pruneMissing] is true (deleted
     *   or no longer readable), or
     * - their fingerprint differs from the candidate's (content replaced), or
     * - they are listed in [forceUris] and still exist (caller knows they changed).
     *
     * Deleting before re-embedding is deliberate: if embedding later fails, the
     * photo remains absent instead of serving an embedding of its old content.
     *
     * @return candidates that need embedding: new rows plus deleted/replaced ones.
     */
    suspend fun reconcile(
        candidates: List<PhotoCandidate>,
        modelId: String,
        pruneMissing: Boolean,
        forceUris: Set<String> = emptySet()
    ): List<PhotoCandidate>

    /**
     * Persists a batch of [entries] (URI + embedding + modelId + fingerprint)
     * in a single database transaction. Re-inserting an existing (modelId, uri)
     * pair replaces the previous row.
     */
    suspend fun putBatch(entries: List<IndexEntry>)

    /**
     * Exact scan: loads all indexed embeddings for [modelId] and ranks them against
     * [embedding] by exact dot-product cosine similarity (highest first).
     * Only embeddings produced by [modelId] are compared, so a text query from model A
     * is never scored against an image embedding from model B.
     */
    suspend fun queryAll(embedding: FloatArray, modelId: String): List<ScoredResult>

    /** Number of indexed rows across all models. */
    suspend fun indexedCount(): Int

    /** Removes every stored embedding for every model. */
    suspend fun clear()
}
