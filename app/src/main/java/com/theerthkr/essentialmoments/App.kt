package com.theerthkr.essentialmoments


import coil.ImageLoader
import coil.ImageLoaderFactory
import com.theerthkr.essentialmoments.concurrency.BackgroundDispatchers
import com.theerthkr.essentialmoments.data.AppDatabase
import com.theerthkr.essentialmoments.data.RoomVectorIndex
import com.theerthkr.essentialmoments.data.VectorIndex
import com.theerthkr.essentialmoments.indexing.IndexingScheduler
import com.theerthkr.essentialmoments.indexing.PhotoIndexer
import com.theerthkr.essentialmoments.media.AndroidMediaCatalog
import com.theerthkr.essentialmoments.media.MediaCatalog
import com.theerthkr.essentialmoments.media.MediaThumbnailFetcher
import com.theerthkr.essentialmoments.ml.EmbeddingModelFactory
import com.theerthkr.essentialmoments.ml.InferenceArbiter
import com.theerthkr.essentialmoments.ml.model.ModelManager
import java.io.File

/**
 * Application entry point. Initializes the deep module graph:
 *
 * - [AppDatabase] → Room database holding the vector index
 * - [mediaCatalog] → canonical photo URIs and albums (Candidate 4)
 * - [vectorIndex] → vector storage and exact search (Candidate 2)
 * - [photoIndexer] → single source of truth for indexing state (Candidate 1)
 * - [InferenceArbiter.DEFAULT] → cooperative hardware priority (Candidate 3)
 */
class App : android.app.Application(), ImageLoaderFactory {

    lateinit var mediaCatalog: MediaCatalog
        private set
    lateinit var vectorIndex: VectorIndex
        private set
    lateinit var photoIndexer: PhotoIndexer
        private set
    lateinit var modelManager: ModelManager
        private set

    override fun onCreate() {
        super.onCreate()

        modelManager = ModelManager(this)

        // Prerelease migration: the previous build stored embeddings in ObjectBox.
        // Room cannot read that format, so drop the stale store and let the
        // indexer regenerate embeddings on its next run.
        deleteLegacyObjectBoxStore()

        // Check if indexing was disabled by user in Settings; cancel any pending worker
        if (!IndexingScheduler.isIndexingEnabled(this)) {
            IndexingScheduler.cancel(this)
        }

        mediaCatalog = AndroidMediaCatalog(contentResolver)
        vectorIndex = RoomVectorIndex(AppDatabase.get(this).imageIndexDao())
        photoIndexer = PhotoIndexer(
            mediaCatalog = mediaCatalog,
            vectorIndex = vectorIndex,
            imageEmbedderFactory = {
                // Low-priority wrapper: yields to interactive text queries (Candidate 3)
                InferenceArbiter.DEFAULT.wrapImageEmbedder(
                    EmbeddingModelFactory.createImageEmbedder(this)
                )
            }
        )
    }

    /**
     * Removes the ObjectBox store left behind by pre-Room builds. The directory
     * is small on a fresh install and can be tens of MB for a large library.
     */
    private fun deleteLegacyObjectBoxStore() {
        val legacyDir = File(filesDir, "objectbox")
        if (legacyDir.exists()) {
            legacyDir.deleteRecursively()
        }
    }

    /**
     * Single app-wide Coil loader.
     *
     * - Registers [MediaThumbnailFetcher] so gallery grids read MediaStore thumbnails
     *   instead of decoding original photos for every cell.
     * - Runs fetching/decoding on a small background-priority pool, so a fast scroll
     *   cannot starve the UI thread.
     * - Caps BitmapFactory parallelism and drops crossfades (each transition forces
     *   extra draw work on cells that were just composed).
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(MediaThumbnailFetcher.Factory()) }
            .fetcherDispatcher(BackgroundDispatchers.imageLoading)
            .decoderDispatcher(BackgroundDispatchers.imageLoading)
            .bitmapFactoryMaxParallelism(2)
            .crossfade(false)
            .build()
}
