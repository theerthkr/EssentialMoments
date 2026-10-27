package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.data.InMemoryVectorIndex
import com.theerthkr.essentialmoments.data.VectorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryVectorIndexTest {

    private val MODEL_ID = "modelA"

    @Test
    fun testFilterUnindexed() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("uri1", floatArrayOf(1.0f, 0.0f), MODEL_ID)
        ))

        val unindexed = index.filterUnindexed(listOf("uri1", "uri2", "uri3"), MODEL_ID)
        assertEquals(listOf("uri2", "uri3"), unindexed)
    }

    @Test
    fun testFilterUnindexedIsScopedByModelId() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("uri1", floatArrayOf(1.0f, 0.0f), "modelA")
        ))

        // Same URI is still "unindexed" under a different modelId
        val unindexed = index.filterUnindexed(listOf("uri1"), "modelB")
        assertEquals(listOf("uri1"), unindexed)
    }

    @Test
    fun testQueryAndRanking() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("target", floatArrayOf(1.0f, 0.0f), MODEL_ID),
            VectorIndex.IndexEntry("semi", floatArrayOf(0.7f, 0.7f), MODEL_ID),
            VectorIndex.IndexEntry("opposite", floatArrayOf(-1.0f, 0.0f), MODEL_ID)
        ))

        val query = floatArrayOf(1.0f, 0.0f)
        val results = index.queryAll(query, MODEL_ID)

        assertEquals(3, results.size)
        assertEquals("target", results[0].uri)
        assertTrue(results[0].score > results[1].score)
        assertEquals("semi", results[1].uri)
    }

    @Test
    fun testQueryAllIsScopedByModelId() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("a", floatArrayOf(1.0f, 0.0f), "modelA"),
            VectorIndex.IndexEntry("b", floatArrayOf(1.0f, 0.0f), "modelB")
        ))

        val results = index.queryAll(floatArrayOf(1.0f, 0.0f), "modelA")
        assertEquals(1, results.size)
        assertEquals("a", results[0].uri)
    }

    @Test
    fun testQueryAllReturnsAllRanked() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("a", floatArrayOf(0.5f, 0.5f), MODEL_ID),
            VectorIndex.IndexEntry("b", floatArrayOf(1.0f, 0.0f), MODEL_ID),
            VectorIndex.IndexEntry("c", floatArrayOf(0.0f, 1.0f), MODEL_ID)
        ))

        val results = index.queryAll(floatArrayOf(1.0f, 0.0f), MODEL_ID)
        assertEquals(3, results.size)
        assertEquals("b", results[0].uri)
    }

    @Test
    fun testQueryAllSkipsDimensionMismatch() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("same", floatArrayOf(1.0f, 0.0f), MODEL_ID),
            VectorIndex.IndexEntry("diff", floatArrayOf(1.0f, 0.0f, 0.0f), MODEL_ID)
        ))

        val results = index.queryAll(floatArrayOf(1.0f, 0.0f), MODEL_ID)
        assertEquals(1, results.size)
        assertEquals("same", results[0].uri)
    }
}
