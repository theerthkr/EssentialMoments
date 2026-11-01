package com.theerthkr.essentialmoments.ml.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.theerthkr.essentialmoments.App

/** Handles the "Pause" action on the model-download notification. */
class ModelDownloadStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? App)?.modelManager?.pauseDownload()
    }
}
