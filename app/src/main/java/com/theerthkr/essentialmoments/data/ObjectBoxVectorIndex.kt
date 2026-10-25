package com.theerthkr.essentialmoments.data

import com.theerthkr.essentialmoments.ImageEntity
import com.theerthkr.essentialmoments.ImageEntity_
import io.objectbox.BoxStore

/**
 * Production adapter for [VectorIndex] backed by ObjectBox with @HnswIndex (COSINE).
 *
 * - [queryTopK] uses ObjectBox's native C++ HNSW graph for sub-millisecond
 *   approximate nearest-neighbor search with near-zero Kotlin heap usage.
 * - [queryAll] provides exact dot-product ranking over the full index.
 * - [filterUnindexed] loads only URI strings (no embeddings) for batch deduplication.
 */
class ObjectBoxVectorIndex(store: BoxStore) : VectorIndex {

    private val box = store.boxFor(ImageEntity::class.java)

    override fun filterUnindexed(uris: List<String>): List<String> {
        if (uris.isEmpty()) return emptyList()

        // Load only URI strings (no embeddings) in a single query
        val query = box.query().build()
        val existingUris: Set<String> = try {
            query.property(ImageEntity_.uri).findStrings().toHashSet()
        } finally {
            query.close()
        }

        return uris.filter { it !in existingUris }
    }

    override fun putBatch(entries: List<VectorIndex.IndexEntry>) {
        if (entries.isEmpty()) return
        val entities = entries.map { entry ->
            ImageEntity(uri = entry.uri, embedding = entry.embedding)
        }
        box.put(entities)
    }

    override fun queryTopK(embedding: FloatArray, topK: Int): List<VectorIndex.ScoredResult> {
        val query = box.query(
            ImageEntity_.embedding.nearestNeighbors(embedding, topK)
        ).build()

        return try {
            query.findWithScores().map { scored ->
                VectorIndex.ScoredResult(
                    uri = scored.get().uri,
                    // ObjectBox COSINE distance = 1 - cosine_similarity
                    // Convert back: similarity = 1.0 - distance
                    score = (1.0 - scored.score).toFloat()
                )
            }
        } finally {
            query.close()
        }
    }

    override fun queryAll(embedding: FloatArray): List<VectorIndex.ScoredResult> {
        val allEntities = box.all
        return allEntities.map { entity ->
            var dot = 0.0f
            val emb = entity.embedding
            for (i in embedding.indices) {
                dot += embedding[i] * emb[i]
            }
            VectorIndex.ScoredResult(uri = entity.uri, score = dot)
        }.sortedByDescending { it.score }
    }

    override val indexedCount: Int
        get() = box.count().toInt()
}
