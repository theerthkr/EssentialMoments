package com.theerthkr.essentialmoments

import android.graphics.Bitmap
import android.net.Uri
import com.theerthkr.essentialmoments.data.InMemoryVectorIndex
import com.theerthkr.essentialmoments.indexing.IndexingState
import com.theerthkr.essentialmoments.indexing.PhotoIndexer
import com.theerthkr.essentialmoments.media.MediaCatalog
import com.theerthkr.essentialmoments.media.PhotoCandidate
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoIndexerTest {

    private class FakeMediaCatalog(
        var candidates: List<PhotoCandidate>
    ) : MediaCatalog {
        override fun allPhotoUris(): List<String> = candidates.map { it.uri }
        override fun photoCandidates(): List<PhotoCandidate> = candidates
        override fun photoCandidate(uri: String): PhotoCandidate? =
            candidates.firstOrNull { it.uri == uri }
        override fun existingUris(candidates: Collection<String>): Set<String> =
            candidates.filter { candidate ->
                this.candidates.any { it.uri == candidate }
            }.toSet()
        override fun albums(): List<Album> = emptyList()
        override fun albumPhotos(albumId: String): List<MediaImage> = emptyList()
        override fun allPhotos(): List<MediaImage> = emptyList()
        override fun observeChanges(): Flow<Unit> = emptyFlow()
        override fun normalizeUri(uri: String): String = uri
    }

    private class FakeImageEmbedder(
        private val failUris: Set<String> = emptySet()
    ) : ImageEmbedder {
        override val modelId: String = "fake"
        override val embeddingDimension: Int = 4

        var calls = 0
            private set

        override fun getEmbedding(uri: Uri): FloatArray? = getEmbedding(uri.toString())

        override fun getEmbedding(uriString: String): FloatArray? {
            calls++
            if (uriString in failUris) return null
            return if (calls % 2 == 0) {
                floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f)
            } else {
                floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
            }
        }

        override fun getEmbedding(bitmap: Bitmap, onResized: ((Bitmap) -> Unit)?): FloatArray =
            floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)

        override fun close() {}
    }

    private fun candidate(uri: String, fingerprint: String = "fp") =
        PhotoCandidate(uri, fingerprint)

    private fun createIndexer(
        catalog: MediaCatalog,
        vectorIndex: InMemoryVectorIndex,
        embedder: () -> ImageEmbedder = { FakeImageEmbedder() }
    ) = PhotoIndexer(
        mediaCatalog = catalog,
        vectorIndex = vectorIndex,
        imageModelId = { "fake" },
        imageEmbedderFactory = embedder,
        batchSize = 2
    )

    private fun done(state: IndexingState): IndexingState.Done =
        state as IndexingState.Done

    @Test
    fun testIndexAllProcessesOnlyUnindexedPhotos() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(
            listOf(candidate("photo1"), candidate("photo2"), candidate("photo3"))
        )
        val indexer = createIndexer(mediaCatalog, vectorIndex)

        indexer.indexAll()
        assertEquals(3, vectorIndex.indexedCount())
        assertEquals(3, done(indexer.state.value).indexed)
        assertEquals(0, done(indexer.state.value).skipped)

        // Second run with no new photos
        indexer.indexAll()
        assertEquals(3, vectorIndex.indexedCount())
        assertEquals(0, done(indexer.state.value).indexed)
    }

    @Test
    fun testIndexSelectedUris() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(listOf(candidate("p1"), candidate("p2")))
        val indexer = createIndexer(mediaCatalog, vectorIndex)

        indexer.indexUris(listOf("p1", "p2"))
        assertEquals(2, vectorIndex.indexedCount())
        assertEquals(2, done(indexer.state.value).indexed)

        indexer.resetState()
        assertTrue(indexer.state.value is IndexingState.Idle)
    }

    @Test
    fun testUnreadablePhotoIsSkippedNotCounted() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(
            listOf(candidate("good"), candidate("broken"))
        )
        val indexer = createIndexer(
            mediaCatalog,
            vectorIndex,
            embedder = { FakeImageEmbedder(failUris = setOf("broken")) }
        )

        indexer.indexAll()

        assertEquals(1, vectorIndex.indexedCount())
        assertEquals(1, done(indexer.state.value).indexed)
        assertEquals(1, done(indexer.state.value).skipped)
    }

    @Test
    fun testDeletedPhotoIsRemovedOnRescan() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(
            listOf(candidate("kept"), candidate("deleted"))
        )
        val indexer = createIndexer(mediaCatalog, vectorIndex)

        indexer.indexAll()
        assertEquals(2, vectorIndex.indexedCount())

        mediaCatalog.candidates = listOf(candidate("kept"))
        indexer.indexAll()

        assertEquals(1, vectorIndex.indexedCount())
        assertEquals(listOf("kept"), vectorIndex.queryAll(floatArrayOf(1f, 0f, 0f, 0f), "fake").map { it.uri })
    }

    @Test
    fun testChangedPhotoIsDeletedAndReembedded() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(listOf(candidate("photo", "old")))
        val embedder = FakeImageEmbedder()
        val indexer = createIndexer(mediaCatalog, vectorIndex, embedder = { embedder })

        indexer.indexAll()
        assertEquals(1, embedder.calls)

        mediaCatalog.candidates = listOf(candidate("photo", "new"))
        indexer.indexAll()

        assertEquals(2, embedder.calls)
        assertEquals(1, done(indexer.state.value).indexed)
        assertEquals(1, vectorIndex.indexedCount())
    }

    @Test
    fun testUnchangedScanDoesNotConstructEmbedder() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(listOf(candidate("photo")))
        var constructed = false
        val indexer = createIndexer(
            mediaCatalog,
            vectorIndex,
            embedder = {
                constructed = true
                FakeImageEmbedder()
            }
        )

        indexer.indexAll()
        assertEquals(1, vectorIndex.indexedCount())
        constructed = false

        indexer.indexAll()
        assertEquals(false, constructed)
        assertEquals(0, done(indexer.state.value).indexed)
    }

    @Test
    fun testReconcileNowDeletesMissingRowWithoutEmbedding() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(listOf(candidate("kept"), candidate("gone")))
        var constructed = false
        val indexer = createIndexer(
            mediaCatalog,
            vectorIndex,
            embedder = {
                constructed = true
                FakeImageEmbedder()
            }
        )

        indexer.indexAll()
        assertEquals(2, vectorIndex.indexedCount())

        mediaCatalog.candidates = listOf(candidate("kept"))
        constructed = false
        indexer.reconcileNow()

        assertEquals(1, vectorIndex.indexedCount())
        assertEquals(false, constructed)
    }
}
