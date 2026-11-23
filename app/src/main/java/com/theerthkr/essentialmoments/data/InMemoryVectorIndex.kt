package com.theerthkr.essentialmoments.data

import com.theerthkr.essentialmoments.media.PhotoCandidate
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory adapter for [VectorIndex].
 *
 * Provides fast, deterministic vector storage and scoring on the JVM without
 * Room/SQLite dependencies. Ideal for unit tests and local simulation.
 *
 * Storage is nested by modelId then URI, so embeddings from different models
 * never collide and are never compared against each other.
 */
class InMemoryVectorIndex : VectorIndex {

    private data class Stored(val embedding: FloatArray, val fingerprint: String)

    private val storage = ConcurrentHashMap<String, ConcurrentHashMap<String, Stored>>()

    override suspend fun reconcile(
        candidates: List<PhotoCandidate>,
        modelId: String,
        pruneMissing: Boolean,
        forceUris: Set<String>
    ): List<PhotoCandidate> {
        val byModel = storage[modelId]
        val candidateUris = candidates.mapTo(HashSet(candidates.size)) { it.uri }
        val toIndex = ArrayList<PhotoCandidate>(candidates.size)

        for (candidate in candidates) {
            val existing = byModel?.get(candidate.uri)
            val changed = existing != null && existing.fingerprint != candidate.fingerprint
            if (existing == null || changed || candidate.uri in forceUris) {
                toIndex.add(candidate)
                if (existing != null) byModel?.remove(candidate.uri)
            }
        }

        if (byModel != null) {
            if (pruneMissing) {
                byModel.keys.removeAll { it !in candidateUris }
            }
            for (uri in forceUris) {
                if (uri !in candidateUris) byModel.remove(uri)
            }
        }

        return toIndex
    }

    override suspend fun putBatch(entries: List<VectorIndex.IndexEntry>) {
        if (entries.isEmpty()) return
        for (entry in entries) {
            storage.computeIfAbsent(entry.modelId) { ConcurrentHashMap() }[entry.uri] =
                Stored(entry.embedding, entry.fingerprint)
        }
    }

    override suspend fun queryAll(
        embedding: FloatArray,
        modelId: String
    ): List<VectorIndex.ScoredResult> {
        val byModel = storage[modelId] ?: return emptyList()
        return byModel.mapNotNull { (uri, stored) ->
            val emb = stored.embedding
            if (emb.isEmpty() || emb.size != embedding.size) return@mapNotNull null
            VectorIndex.ScoredResult(uri = uri, score = dotProduct(embedding, emb))
        }.sortedByDescending { it.score }
    }

    override suspend fun indexedCount(): Int = storage.values.sumOf { it.size }

    override suspend fun clear() {
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
