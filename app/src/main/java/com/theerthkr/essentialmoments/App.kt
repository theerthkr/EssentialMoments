package com.theerthkr.essentialmoments


import com.theerthkr.essentialmoments.data.ObjectBoxVectorIndex
import com.theerthkr.essentialmoments.data.VectorIndex
import com.theerthkr.essentialmoments.indexing.PhotoIndexer
import com.theerthkr.essentialmoments.media.AndroidMediaCatalog
import com.theerthkr.essentialmoments.media.MediaCatalog
import com.theerthkr.essentialmoments.ml.EmbeddingModelFactory
import com.theerthkr.essentialmoments.ml.InferenceArbiter

// ObjectBox singleton — initializes the database once for the whole app.
object ObjectBox {
    lateinit var store: io.objectbox.BoxStore
        private set

    fun init(context: android.content.Context) {
        store = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
    }
}

/**
 * Application entry point. Initializes the deep module graph:
 *
 * - [ObjectBox] → raw database
 * - [mediaCatalog] → canonical photo URIs and albums (Candidate 4)
 * - [vectorIndex] → HNSW vector storage and search (Candidate 2)
 * - [photoIndexer] → single source of truth for indexing state (Candidate 1)
 * - [InferenceArbiter.DEFAULT] → cooperative hardware priority (Candidate 3)
 */
class App : android.app.Application() {

    lateinit var mediaCatalog: MediaCatalog
        private set
    lateinit var vectorIndex: VectorIndex
        private set
    lateinit var photoIndexer: PhotoIndexer
        private set

    override fun onCreate() {
        super.onCreate()
        ObjectBox.init(this)

        mediaCatalog = AndroidMediaCatalog(contentResolver)
        vectorIndex = ObjectBoxVectorIndex(ObjectBox.store)
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
}
