package com.theerthkr.essentialmoments.data

/**
 * Production adapter for [VectorIndex] backed by Room.
 *
 * - [filterUnindexed] loads only the URIs already indexed for one model, so a
 *   large library never materializes embedding blobs just to deduplicate.
 * - [putBatch] upserts URI + embedding + modelId rows in one Room transaction.
 * - [queryAll] performs an exact dot-product scan restricted to one [modelId],
 *   so a text query from model A is never scored against an image embedding
 *   from model B.
 * - Dimensions are guarded: a stored embedding whose size differs from the
 *   query embedding is skipped rather than silently mismatched.
 */
class RoomVectorIndex(private val dao: ImageIndexDao) : VectorIndex {

    override suspend fun filterUnindexed(uris: List<String>, modelId: String): List<String> {
        if (uris.isEmpty()) return emptyList()

        val existingUris: Set<String> = dao.indexedUris(modelId).toHashSet()
        return uris.filter { it !in existingUris }
    }

    override suspend fun putBatch(entries: List<VectorIndex.IndexEntry>) {
        if (entries.isEmpty()) return
        dao.insertAll(
            entries.map { entry ->
                ImageIndexEntity(
                    uri = entry.uri,
                    modelId = entry.modelId,
                    embedding = entry.embedding
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
}
