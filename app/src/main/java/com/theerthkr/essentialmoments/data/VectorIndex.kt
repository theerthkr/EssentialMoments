package com.theerthkr.essentialmoments.data

/**
 * Deep module interface for vector storage, deduplication, and similarity search.
 *
 * Hides the storage engine (ObjectBox), HNSW indexing, deduplication logic,
 * and ranking math behind a small interface. Callers never see ImageEntity,
 * BoxStore, or raw database cursors.
 */
interface VectorIndex {

    data class IndexEntry(val uri: String, val embedding: FloatArray)
    data class ScoredResult(val uri: String, val score: Float)

    /**
     * Batch-filters [uris] and returns only those not yet present in the index.
     * Uses a single set-lookup query instead of N+1 per-URI queries.
     */
    fun filterUnindexed(uris: List<String>): List<String>

    /**
     * Persists a batch of [entries] (URI + embedding pairs) into the index
     * within a single ObjectBox transaction.
     */
    fun putBatch(entries: List<IndexEntry>)

    /**
     * Returns the top [topK] closest photos to [embedding], ranked by
     * cosine similarity (highest first), using HNSW approximate nearest neighbor search.
     *
     * Default topK = 500. Pass a higher value for broader results.
     */
    fun queryTopK(embedding: FloatArray, topK: Int = 500): List<ScoredResult>

    /**
     * Exact scan: loads all indexed embeddings and ranks them against [embedding]
     * by exact dot-product similarity (highest first).
     *
     * Useful for features requiring 100% exact ranking over the full index.
     */
    fun queryAll(embedding: FloatArray): List<ScoredResult>

    /** Number of photos currently indexed. */
    val indexedCount: Int
}
