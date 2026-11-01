package com.theerthkr.essentialmoments.data

/**
 * Deep module interface for vector storage, deduplication, and similarity search.
 *
 * Hides the storage engine (Room), deduplication logic, and ranking math behind a
 * small interface. Callers never see [ImageIndexEntity], the DAO, or raw database
 * cursors.
 *
 * All operations are suspend functions: Room must not be queried on the main
 * thread, and callers already invoke the index from coroutines.
 */
interface VectorIndex {

    data class IndexEntry(val uri: String, val embedding: FloatArray, val modelId: String)
    data class ScoredResult(val uri: String, val score: Float)

    /**
     * Batch-filters [uris] and returns only those not yet present in the index for [modelId].
     * A photo indexed under one modelId is still "unindexed" under another modelId,
     * so the same photo can be embedded by multiple models without collision.
     */
    suspend fun filterUnindexed(uris: List<String>, modelId: String): List<String>

    /**
     * Persists a batch of [entries] (URI + embedding + modelId triples) in a
     * single database transaction. Re-inserting an existing (modelId, uri) pair
     * replaces the previous row.
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
}
