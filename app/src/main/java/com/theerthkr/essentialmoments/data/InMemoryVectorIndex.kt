package com.theerthkr.essentialmoments.data

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory adapter for [VectorIndex].
 *
 * Provides fast, deterministic vector storage and scoring on the JVM without
 * native ObjectBox JNI dependencies. Ideal for unit tests and local simulation.
 *
 * Storage is nested by modelId then URI, so embeddings from different models
 * never collide and are never compared against each other.
 */
class InMemoryVectorIndex : VectorIndex {

    private val storage = ConcurrentHashMap<String, ConcurrentHashMap<String, FloatArray>>()

    override fun filterUnindexed(uris: List<String>, modelId: String): List<String> {
        val byModel = storage[modelId]
        val existingUris = if (byModel == null) emptySet<String>() else byModel.keys.toHashSet()
        return uris.filter { it !in existingUris }
    }

    override fun putBatch(entries: List<VectorIndex.IndexEntry>) {
        if (entries.isEmpty()) return
        for (entry in entries) {
            storage.computeIfAbsent(entry.modelId) { ConcurrentHashMap() }[entry.uri] = entry.embedding
        }
    }

    override fun queryAll(embedding: FloatArray, modelId: String): List<VectorIndex.ScoredResult> {
        val byModel = storage[modelId] ?: return emptyList()
        return byModel.mapNotNull { (uri, emb) ->
            if (emb.isEmpty() || emb.size != embedding.size) return@mapNotNull null
            VectorIndex.ScoredResult(uri = uri, score = dotProduct(embedding, emb))
        }.sortedByDescending { it.score }
    }

    override val indexedCount: Int
        get() = storage.values.sumOf { it.size }

    fun clear() {
        storage.clear()
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }
}
