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
import com.theerthkr.essentialmoments.permissions.AppPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * Process-lifetime scope for work that must outlive a single activity but
     * does not belong to WorkManager (MediaStore change reconciliation).
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mediaChangeEvents = Channel<Unit>(Channel.CONFLATED)

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

        // Reconcile persisted indexing preferences with current permissions;
        // notification or photo access may have changed while the app was dead.
        // Keep this after photoIndexer is initialized: reconcile may cancel a
        // stale worker and reset the indexer state.
        mediaCatalog = AndroidMediaCatalog(contentResolver)
        vectorIndex = RoomVectorIndex(AppDatabase.get(this).imageIndexDao())
        photoIndexer = PhotoIndexer(
            mediaCatalog = mediaCatalog,
            vectorIndex = vectorIndex,
            imageModelId = { EmbeddingModelFactory.visionModelId },
            imageEmbedderFactory = {
                // Low-priority wrapper: yields to interactive text queries (Candidate 3)
                InferenceArbiter.DEFAULT.wrapImageEmbedder(
                    EmbeddingModelFactory.createImageEmbedder(this)
                )
            }
        )

        startMediaChangeObserver()
        IndexingScheduler.reconcile(this)
    }

    /**
     * Watches MediaStore for additions, deletions and replacements.
     *
     * Reconciliation runs in two stages so deletion never waits for the model:
     * 1. [PhotoIndexer.reconcileNow] deletes rows for photos that disappeared or
     *    whose fingerprint changed. It does not need the vision model.
     * 2. A normal indexing pass embeds the photos that were just marked as new.
     *
     * Events are conflated and debounced because MediaStore emits several
     * notifications for a single insert/update and a camera save can produce a
     * burst. Indexing writes only to Room, so this cannot feed back into the
     * observer.
     */
    private fun startMediaChangeObserver() {
        applicationScope.launch {
            mediaCatalog.observeChanges().collect {
                mediaChangeEvents.trySend(Unit)
            }
        }
        applicationScope.launch {
            for (ignored in mediaChangeEvents) {
                delay(MEDIA_CHANGE_DEBOUNCE_MS)
                if (!AppPermissions.mediaAccess(this@App).canReadPhotos) continue

                // Deletion does not need the model or the indexing switch. Run
                // it first so a removed/changed photo can never keep serving an
                // old embedding while re-indexing is pending.
                photoIndexer.reconcileNow()

                if (IndexingScheduler.isIndexingEnabled(this@App) &&
                    EmbeddingModelFactory.isVisionReady(this@App)
                ) {
                    IndexingScheduler.scheduleReconcile(this@App)
                }
            }
        }
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

    private companion object {
        /** Coalesces the insert + update notifications of a single photo change. */
        const val MEDIA_CHANGE_DEBOUNCE_MS = 750L
    }
}
