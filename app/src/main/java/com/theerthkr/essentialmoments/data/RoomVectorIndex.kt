package com.theerthkr.essentialmoments.data

import com.theerthkr.essentialmoments.media.PhotoCandidate

/**
 * Production adapter for [VectorIndex] backed by Room.
 *
 * - [reconcile] compares stored (uri, fingerprint) pairs with the current
 *   MediaStore catalog, deleting rows that disappeared or changed before the
 *   indexer attempts to re-embed them.
 * - [putBatch] upserts URI + embedding + modelId + fingerprint rows in one
 *   Room transaction.
 * - [queryAll] performs an exact dot-product scan restricted to one [modelId],
 *   so a text query from model A is never scored against an image embedding
 *   from model B.
 * - Dimensions are guarded: a stored embedding whose size differs from the
 *   query embedding is skipped rather than silently mismatched.
 */
class RoomVectorIndex(private val dao: ImageIndexDao) : VectorIndex {

    override suspend fun reconcile(
        candidates: List<PhotoCandidate>,
        modelId: String,
        pruneMissing: Boolean,
        forceUris: Set<String>
    ): List<PhotoCandidate> {
        val stored = dao.indexedStates(modelId).associateBy { it.uri }
        val candidateUris = candidates.mapTo(HashSet(candidates.size)) { it.uri }

        val toDelete = LinkedHashSet<String>()
        val toIndex = ArrayList<PhotoCandidate>(candidates.size)

        for (candidate in candidates) {
            val existing = stored[candidate.uri]
            val changed = existing != null && existing.fingerprint != candidate.fingerprint
            if (existing == null || changed || candidate.uri in forceUris) {
                toIndex.add(candidate)
                if (existing != null) toDelete.add(candidate.uri)
            }
        }

        if (pruneMissing) {
            for (uri in stored.keys) {
                if (uri !in candidateUris) toDelete.add(uri)
            }
        }

        // Explicitly forced URIs that are no longer readable must disappear too.
        for (uri in forceUris) {
            if (uri !in candidateUris && uri in stored) toDelete.add(uri)
        }

        if (toDelete.isNotEmpty()) {
            for (chunk in toDelete.chunked(DELETE_CHUNK_SIZE)) {
                dao.deleteByUris(modelId, chunk)
            }
        }

        return toIndex
    }

    override suspend fun putBatch(entries: List<VectorIndex.IndexEntry>) {
        if (entries.isEmpty()) return
        dao.insertAll(
            entries.map { entry ->
                ImageIndexEntity(
                    uri = entry.uri,
                    modelId = entry.modelId,
                    embedding = entry.embedding,
                    fingerprint = entry.fingerprint
                )
            }
        )
    }

    override suspend fun queryAll(
        embedding: FloatArray,
        modelId: String
    ): List<VectorIndex.ScoredResult> =
        dao.embeddingsForModel(modelId)
            .mapNotNull { row ->
                val stored = row.embedding
                if (stored.isEmpty() || stored.size != embedding.size) return@mapNotNull null
                var dot = 0.0f
                for (i in stored.indices) {
                    dot += embedding[i] * stored[i]
                }
                VectorIndex.ScoredResult(uri = row.uri, score = dot)
            }
            .sortedByDescending { it.score }

    override suspend fun indexedCount(): Int = dao.count()

    override suspend fun clear() = dao.clearAll()

    private companion object {
        /** Keep every bind list comfortably below SQLite's variable limit. */
        const val DELETE_CHUNK_SIZE = 500
    }
}
