package com.theerthkr.essentialmoments.data

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory adapter for [VectorIndex].
 *
 * Provides fast, deterministic vector storage and scoring on the JVM without
 * native ObjectBox JNI dependencies. Ideal for unit tests and local simulation.
 */
class InMemoryVectorIndex : VectorIndex {

    private val storage = ConcurrentHashMap<String, FloatArray>()

    override fun filterUnindexed(uris: List<String>): List<String> =
        uris.filter { !storage.containsKey(it) }

    override fun putBatch(entries: List<VectorIndex.IndexEntry>) {
        for (entry in entries) {
            storage[entry.uri] = entry.embedding
        }
    }

    override fun queryTopK(embedding: FloatArray, topK: Int): List<VectorIndex.ScoredResult> =
        queryAll(embedding).take(topK)

    override fun queryAll(embedding: FloatArray): List<VectorIndex.ScoredResult> =
        storage.map { (uri, emb) ->
            VectorIndex.ScoredResult(uri = uri, score = dotProduct(embedding, emb))
        }.sortedByDescending { it.score }

    override val indexedCount: Int
        get() = storage.size

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
