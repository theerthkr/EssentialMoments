package com.theerthkr.essentialmoments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.theerthkr.essentialmoments.indexing.IndexingScheduler

/**
 * Handles the "Stop" action from the indexing notification.
 * Delegates to [IndexingScheduler] — the single place that owns the WorkManager key.
 */
class StopIndexingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IndexingScheduler.cancel(context)
    }
}
