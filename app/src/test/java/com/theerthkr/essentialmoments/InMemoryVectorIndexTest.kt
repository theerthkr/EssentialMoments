package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.data.InMemoryVectorIndex
import com.theerthkr.essentialmoments.data.VectorIndex
import com.theerthkr.essentialmoments.media.PhotoCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryVectorIndexTest {

    private val MODEL_ID = "modelA"

    private fun candidate(uri: String, fingerprint: String = "fp") =
        PhotoCandidate(uri, fingerprint)

    private fun entry(uri: String, vararg embedding: Float, fingerprint: String = "fp") =
        VectorIndex.IndexEntry(uri, floatArrayOf(*embedding), MODEL_ID, fingerprint)

    @Test
    fun testReconcileReturnsOnlyNewCandidates() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("uri1", 1.0f, 0.0f)))

        val pending = index.reconcile(
            candidates = listOf(candidate("uri1"), candidate("uri2"), candidate("uri3")),
            modelId = MODEL_ID,
            pruneMissing = false
        )

        assertEquals(listOf("uri2", "uri3"), pending.map { it.uri })
    }

    @Test
    fun testReconcileIsScopedByModelId() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("uri1", 1.0f, 0.0f)))

        // Same URI is still "unindexed" under a different modelId
        val pending = index.reconcile(
            candidates = listOf(candidate("uri1")),
            modelId = "modelB",
            pruneMissing = false
        )
        assertEquals(listOf("uri1"), pending.map { it.uri })
    }

    @Test
    fun testReconcilePrunesMissingWhenRequested() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("kept", 1.0f, 0.0f), entry("gone", 1.0f, 0.0f)))

        index.reconcile(
            candidates = listOf(candidate("kept")),
            modelId = MODEL_ID,
            pruneMissing = true
        )

        assertEquals(1, index.indexedCount())
        assertEquals(listOf("kept"), index.queryAll(floatArrayOf(1f, 0f), MODEL_ID).map { it.uri })
    }

    @Test
    fun testReconcileKeepsMissingWhenNotPruning() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("kept", 1.0f, 0.0f), entry("elsewhere", 1.0f, 0.0f)))

        index.reconcile(
            candidates = listOf(candidate("kept")),
            modelId = MODEL_ID,
            pruneMissing = false
        )

        assertEquals(2, index.indexedCount())
    }

    @Test
    fun testReconcileReembedsChangedFingerprint() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("photo", 1.0f, 0.0f, fingerprint = "old")))

        val pending = index.reconcile(
            candidates = listOf(candidate("photo", "new")),
            modelId = MODEL_ID,
            pruneMissing = true
        )

        assertEquals(listOf("photo"), pending.map { it.uri })
        // The old embedding was removed; only a later putBatch restores it.
        assertEquals(0, index.indexedCount())
    }

    @Test
    fun testReconcileForcedUriIsReembedded() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("photo", 1.0f, 0.0f)))

        val pending = index.reconcile(
            candidates = listOf(candidate("photo")),
            modelId = MODEL_ID,
            pruneMissing = false,
            forceUris = setOf("photo")
        )

        assertEquals(listOf("photo"), pending.map { it.uri })
        assertEquals(0, index.indexedCount())
    }

    @Test
    fun testReconcileForcedMissingUriIsDeleted() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("gone", 1.0f, 0.0f)))

        index.reconcile(
            candidates = emptyList(),
            modelId = MODEL_ID,
            pruneMissing = false,
            forceUris = setOf("gone")
        )

        assertEquals(0, index.indexedCount())
    }

    @Test
    fun testQueryAndRanking() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            entry("target", 1.0f, 0.0f),
            entry("semi", 0.7f, 0.7f),
            entry("opposite", -1.0f, 0.0f)
        ))

        val query = floatArrayOf(1.0f, 0.0f)
        val results = index.queryAll(query, MODEL_ID)

        assertEquals(3, results.size)
        assertEquals("target", results[0].uri)
        assertTrue(results[0].score > results[1].score)
        assertEquals("semi", results[1].uri)
    }

    @Test
    fun testQueryAllIsScopedByModelId() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("a", floatArrayOf(1.0f, 0.0f), "modelA", "fp"),
            VectorIndex.IndexEntry("b", floatArrayOf(1.0f, 0.0f), "modelB", "fp")
        ))

        val results = index.queryAll(floatArrayOf(1.0f, 0.0f), "modelA")
        assertEquals(1, results.size)
        assertEquals("a", results[0].uri)
    }

    @Test
    fun testQueryAllReturnsAllRanked() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            entry("a", 0.5f, 0.5f),
            entry("b", 1.0f, 0.0f),
            entry("c", 0.0f, 1.0f)
        ))

        val results = index.queryAll(floatArrayOf(1.0f, 0.0f), MODEL_ID)
        assertEquals(3, results.size)
        assertEquals("b", results[0].uri)
    }

    @Test
    fun testQueryAllSkipsDimensionMismatch() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            entry("same", 1.0f, 0.0f),
            entry("diff", 1.0f, 0.0f, 0.0f)
        ))

        val results = index.queryAll(floatArrayOf(1.0f, 0.0f), MODEL_ID)
        assertEquals(1, results.size)
        assertEquals("same", results[0].uri)
    }

    @Test
    fun testClearRemovesEverything() = runBlocking {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(entry("a", 1.0f, 0.0f)))

        index.clear()

        assertEquals(0, index.indexedCount())
    }
}
