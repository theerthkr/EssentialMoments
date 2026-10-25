package com.theerthkr.essentialmoments.ml

import android.graphics.Bitmap
import android.net.Uri
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Coordinates hardware execution between interactive high-priority text queries
 * and background low-priority photo indexing batches.
 *
 * Background [ImageEmbedder] calls yield between individual photos whenever one or more
 * high-priority [TextEmbedder] queries are waiting, avoiding NPU/CPU/memory contention
 * without killing or restarting Android WorkManager jobs.
 */
class InferenceArbiter {

    private val lock = ReentrantLock(true)
    private val highPriorityCleared = lock.newCondition()
    private val pendingHighPriority = AtomicInteger(0)

    /**
     * Runs a high-priority interactive operation (such as query text embedding),
     * preempting queued low-priority indexing work as soon as the current frame completes.
     */
    fun <T> runHighPriority(block: () -> T): T {
        pendingHighPriority.incrementAndGet()
        try {
            return lock.withLock {
                block()
            }
        } finally {
            if (pendingHighPriority.decrementAndGet() == 0) {
                lock.withLock {
                    highPriorityCleared.signalAll()
                }
            }
        }
    }

    /**
     * Runs a low-priority background operation (such as single-photo embedding),
     * waiting if any high-priority operation is currently pending or executing.
     */
    fun <T> runLowPriority(block: () -> T): T {
        return lock.withLock {
            while (pendingHighPriority.get() > 0) {
                highPriorityCleared.await()
            }
            block()
        }
    }

    val activeHighPriorityCount: Int
        get() = pendingHighPriority.get()

    fun wrapImageEmbedder(delegate: ImageEmbedder): ImageEmbedder =
        ArbitratedImageEmbedder(delegate, this)

    fun wrapTextEmbedder(delegate: TextEmbedder): TextEmbedder =
        ArbitratedTextEmbedder(delegate, this)

    private class ArbitratedImageEmbedder(
        private val delegate: ImageEmbedder,
        private val arbiter: InferenceArbiter
    ) : ImageEmbedder {
        override val modelId: String get() = delegate.modelId
        override val embeddingDimension: Int get() = delegate.embeddingDimension

        override fun getEmbedding(uri: Uri): FloatArray? =
            arbiter.runLowPriority { delegate.getEmbedding(uri) }

        override fun getEmbedding(uriString: String): FloatArray? =
            arbiter.runLowPriority { delegate.getEmbedding(uriString) }

        override fun getEmbedding(bitmap: Bitmap, onResized: ((Bitmap) -> Unit)?): FloatArray =
            arbiter.runLowPriority { delegate.getEmbedding(bitmap, onResized) }

        override fun close() {
            delegate.close()
        }
    }

    private class ArbitratedTextEmbedder(
        private val delegate: TextEmbedder,
        private val arbiter: InferenceArbiter
    ) : TextEmbedder {
        override val modelId: String get() = delegate.modelId
        override val embeddingDimension: Int get() = delegate.embeddingDimension

        override fun getEmbedding(text: String): FloatArray =
            arbiter.runHighPriority { delegate.getEmbedding(text) }

        override fun close() {
            delegate.close()
        }
    }

    companion object {
        val DEFAULT = InferenceArbiter()
    }
}
