package com.theerthkr.essentialmoments.data

/**
 * Deep module interface for vector storage, deduplication, and similarity search.
 *
 * Hides the storage engine (ObjectBox), deduplication logic,
 * and ranking math behind a small interface. Callers never see ImageEntity,
 * BoxStore, or raw database cursors.
 */
interface VectorIndex {

    data class IndexEntry(val uri: String, val embedding: FloatArray, val modelId: String)
    data class ScoredResult(val uri: String, val score: Float)

    /**
     * Batch-filters [uris] and returns only those not yet present in the index for [modelId].
     * Uses a single set-lookup query instead of N+1 per-URI queries.
     * A photo indexed under one modelId is still "unindexed" under another modelId,
     * so the same photo can be embedded by multiple models without collision.
     */
    fun filterUnindexed(uris: List<String>, modelId: String): List<String>

    /**
     * Persists a batch of [entries] (URI + embedding + modelId triples) into the index
     * within a single ObjectBox transaction.
     */
    fun putBatch(entries: List<IndexEntry>)

    /**
     * Exact scan: loads all indexed embeddings for [modelId] and ranks them against [embedding]
     * by exact dot-product cosine similarity (highest first).
     * Only embeddings produced by [modelId] are compared, so a text query from model A
     * is never scored against an image embedding from model B.
     */
    fun queryAll(embedding: FloatArray, modelId: String): List<ScoredResult>

    /** Number of photos currently indexed. */
    val indexedCount: Int
}
