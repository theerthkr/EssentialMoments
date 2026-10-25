package com.theerthkr.essentialmoments

import android.graphics.Bitmap
import android.net.Uri
import com.theerthkr.essentialmoments.data.InMemoryVectorIndex
import com.theerthkr.essentialmoments.indexing.IndexingState
import com.theerthkr.essentialmoments.indexing.PhotoIndexer
import com.theerthkr.essentialmoments.media.MediaCatalog
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoIndexerTest {

    private class FakeMediaCatalog(
        private val uris: List<String>
    ) : MediaCatalog {
        override fun allPhotoUris(): List<String> = uris
        override fun albums(): List<Album> = emptyList()
        override fun albumPhotos(albumId: String): List<MediaImage> = emptyList()
        override fun allPhotos(): List<MediaImage> = emptyList()
        override fun normalizeUri(uri: String): String = uri
    }

    private class FakeImageEmbedder : ImageEmbedder {
        override val modelId: String = "fake"
        override val embeddingDimension: Int = 4

        override fun getEmbedding(uri: Uri): FloatArray =
            floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)

        override fun getEmbedding(uriString: String): FloatArray =
            floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)

        override fun getEmbedding(bitmap: Bitmap, onResized: ((Bitmap) -> Unit)?): FloatArray =
            floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)

        override fun close() {}
    }

    @Test
    fun testIndexAllProcessesOnlyUnindexedPhotos() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(listOf("photo1", "photo2", "photo3"))
        val indexer = PhotoIndexer(
            mediaCatalog = mediaCatalog,
            vectorIndex = vectorIndex,
            imageEmbedderFactory = { FakeImageEmbedder() },
            batchSize = 2
        )

        indexer.indexAll()
        assertEquals(3, vectorIndex.indexedCount)
        assertTrue(indexer.state.value is IndexingState.Done)
        assertEquals(3, (indexer.state.value as IndexingState.Done).count)

        // Second run with no new photos
        indexer.indexAll()
        assertEquals(3, vectorIndex.indexedCount)
        assertEquals(0, (indexer.state.value as IndexingState.Done).count)
    }

    @Test
    fun testIndexSelectedUris() = runBlocking {
        val vectorIndex = InMemoryVectorIndex()
        val mediaCatalog = FakeMediaCatalog(emptyList())
        val indexer = PhotoIndexer(
            mediaCatalog = mediaCatalog,
            vectorIndex = vectorIndex,
            imageEmbedderFactory = { FakeImageEmbedder() },
            batchSize = 2
        )

        indexer.indexUris(listOf("p1", "p2"))
        assertEquals(2, vectorIndex.indexedCount)
        assertEquals(2, (indexer.state.value as IndexingState.Done).count)
    }
}
