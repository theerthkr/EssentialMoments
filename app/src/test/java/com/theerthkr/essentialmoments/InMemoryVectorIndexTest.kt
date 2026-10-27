package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.data.InMemoryVectorIndex
import com.theerthkr.essentialmoments.data.VectorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryVectorIndexTest {

    @Test
    fun testFilterUnindexed() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("uri1", floatArrayOf(1.0f, 0.0f))
        ))

        val unindexed = index.filterUnindexed(listOf("uri1", "uri2", "uri3"))
        assertEquals(listOf("uri2", "uri3"), unindexed)
    }

    @Test
    fun testQueryAndRanking() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("target", floatArrayOf(1.0f, 0.0f)),
            VectorIndex.IndexEntry("semi", floatArrayOf(0.7f, 0.7f)),
            VectorIndex.IndexEntry("opposite", floatArrayOf(-1.0f, 0.0f))
        ))

        val query = floatArrayOf(1.0f, 0.0f)
        val results = index.queryAll(query)

        assertEquals(3, results.size)
        assertEquals("target", results[0].uri)
        assertTrue(results[0].score > results[1].score)
        assertEquals("semi", results[1].uri)
    }

    @Test
    fun testQueryAllReturnsAllRanked() {
        val index = InMemoryVectorIndex()
        index.putBatch(listOf(
            VectorIndex.IndexEntry("a", floatArrayOf(0.5f, 0.5f)),
            VectorIndex.IndexEntry("b", floatArrayOf(1.0f, 0.0f)),
            VectorIndex.IndexEntry("c", floatArrayOf(0.0f, 1.0f))
        ))

        val results = index.queryAll(floatArrayOf(1.0f, 0.0f))
        assertEquals(3, results.size)
        assertEquals("b", results[0].uri)
    }
}
