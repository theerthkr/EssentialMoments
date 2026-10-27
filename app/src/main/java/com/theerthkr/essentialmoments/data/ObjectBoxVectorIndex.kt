package com.theerthkr.essentialmoments.data

import com.theerthkr.essentialmoments.ImageEntity
import com.theerthkr.essentialmoments.ImageEntity_
import io.objectbox.BoxStore

/**
 * Production adapter for [VectorIndex] backed by ObjectBox.
 *
 * - [queryAll] provides exact dot-product cosine similarity ranking over the full index.
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

    override fun queryAll(embedding: FloatArray): List<VectorIndex.ScoredResult> {
        val allEntities = box.all
        return allEntities.mapNotNull { entity ->
            val emb = entity.embedding
            if (emb.isEmpty()) return@mapNotNull null
            var dot = 0.0f
            val len = minOf(embedding.size, emb.size)
            for (i in 0 until len) {
                dot += embedding[i] * emb[i]
            }
            VectorIndex.ScoredResult(uri = entity.uri, score = dot)
        }.sortedByDescending { it.score }
    }

    override val indexedCount: Int
        get() = box.count().toInt()
}
