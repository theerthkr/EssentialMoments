package com.theerthkr.essentialmoments

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.theerthkr.essentialmoments.ui.model.ModelDownloadScreen
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme

/** Standalone entry point for the model manager (Settings, gallery banner, search gate). */
class ModelDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EssentialMomentsTheme {
                ModelDownloadScreen(
                    onBack = { finish() },
                    onContinue = { finish() }
                )
            }
        }
    }
}
