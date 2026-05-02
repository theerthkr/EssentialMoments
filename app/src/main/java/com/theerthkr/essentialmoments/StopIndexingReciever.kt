package com.theerthkr.essentialmoments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

class StopIndexingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // When the button is clicked, cancel the unique work task
        WorkManager.getInstance(context).cancelUniqueWork("image_indexing_task")
    }
}