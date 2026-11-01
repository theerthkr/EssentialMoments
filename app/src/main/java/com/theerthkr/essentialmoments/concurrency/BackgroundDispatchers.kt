package com.theerthkr.essentialmoments.concurrency

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Bounded, background-priority dispatchers for work that must never compete with
 * the UI thread: image decoding and background photo indexing.
 *
 * Both pools run at [Process.THREAD_PRIORITY_BACKGROUND] so the scheduler gives the
 * UI thread priority even while dozens of thumbnails are being fetched during a fast
 * scroll.
 */
object BackgroundDispatchers {

    /**
     * Two threads: enough to keep a gallery grid filling, few enough that a fast
     * scroll cannot saturate every core or the storage controller.
     */
    val imageLoading: CoroutineDispatcher = pool("em-image", 2)

    /**
     * One thread: photo indexing is a long-running background job and must never
     * starve rendering, scrolling, or interactive inference.
     */
    val indexing: CoroutineDispatcher = pool("em-index", 1)

    private fun pool(name: String, threads: Int): CoroutineDispatcher =
        Executors.newFixedThreadPool(threads) { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()
}
