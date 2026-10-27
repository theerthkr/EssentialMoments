package com.theerthkr.essentialmoments.data

import com.theerthkr.essentialmoments.ImageEntity
import io.objectbox.BoxStore

/**
 * Production adapter for [VectorIndex] backed by ObjectBox.
 *
 * - [filterUnindexed] dedupes per modelId: a URI is only "indexed" under the modelId
 *   that produced its embedding.
 * - [putBatch] persists URI + embedding + modelId triples in one ObjectBox transaction.
 * - [queryAll] exact dot-product cosine scan restricted to one [modelId], so a text
 *   query from model A is never scored against an image embedding from model B.
 * - Dimensions are guarded: a stored embedding whose size differs from the query
 *   embedding is skipped rather than silently mismatched.
 */
class ObjectBoxVectorIndex(store: BoxStore) : VectorIndex {

    private val box = store.boxFor(ImageEntity::class.java)

    override fun filterUnindexed(uris: List<String>, modelId: String): List<String> {
        if (uris.isEmpty()) return emptyList()

        val query = box.query().build()
        val existingUris: Set<String> = try {
            query.find()
                .filter { it.modelId == modelId }
                .map { it.uri }
                .toHashSet()
        } finally {
            query.close()
        }

        return uris.filter { it !in existingUris }
    }

    override fun putBatch(entries: List<VectorIndex.IndexEntry>) {
        if (entries.isEmpty()) return
        val entities = entries.map { entry ->
            ImageEntity(uri = entry.uri, embedding = entry.embedding, modelId = entry.modelId)
        }
        box.put(entities)
    }

    override fun queryAll(embedding: FloatArray, modelId: String): List<VectorIndex.ScoredResult> {
        val query = box.query().build()
        val allEntities = try { query.find() } finally { query.close() }
        return allEntities
            .filter { it.modelId == modelId }
            .mapNotNull { entity ->
                val emb = entity.embedding
                if (emb.isEmpty() || emb.size != embedding.size) return@mapNotNull null
                var dot = 0.0f
                val len = emb.size
                for (i in 0 until len) {
                    dot += embedding[i] * emb[i]
                }
                VectorIndex.ScoredResult(uri = entity.uri, score = dot)
            }
            .sortedByDescending { it.score }
    }

    override val indexedCount: Int
        get() = box.count().toInt()
}
