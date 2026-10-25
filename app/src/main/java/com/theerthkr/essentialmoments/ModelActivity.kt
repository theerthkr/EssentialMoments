package com.theerthkr.essentialmoments

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theerthkr.essentialmoments.ml.EmbeddingModelFactory
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ModelActivity : ComponentActivity() {
    private fun startIndexing(uris: List<Uri>) {
        val uriStrings = uris.map { it.toString() }.toTypedArray()
        val inputData = androidx.work.workDataOf("KEY_IMAGE_URIS" to uriStrings)

        val indexingRequest = androidx.work.OneTimeWorkRequestBuilder<IndexingWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "image_indexing_task",
            androidx.work.ExistingWorkPolicy.REPLACE, // Restart if clicked again
            indexingRequest
        )
    }
    fun triggerIndexing(indexAll: Boolean = false, selectedUris: List<Uri> = emptyList()) {
        val inputData = androidx.work.workDataOf(
            "KEY_INDEX_ALL" to indexAll,
            "KEY_IMAGE_URIS" to selectedUris.map { it.toString() }.toTypedArray()
        )

        val indexingRequest = androidx.work.OneTimeWorkRequestBuilder<IndexingWorker>()
            .setInputData(inputData)
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            "image_indexing_task",
            androidx.work.ExistingWorkPolicy.REPLACE,
            indexingRequest
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EssentialMomentsTheme {
                val workInfos by WorkManager.getInstance(this)
                    .getWorkInfosForUniqueWorkFlow("image_indexing_task")
                    .collectAsStateWithLifecycle(emptyList())

                val currentStatus = when (workInfos.firstOrNull()?.state) {
                    WorkInfo.State.RUNNING -> "Status: Indexing in progress..."
                    WorkInfo.State.ENQUEUED -> "Status: Waiting to start..."
                    WorkInfo.State.SUCCEEDED -> "Status: Indexing Complete!"
                    else -> "Status: Idle"
                }
                var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

                val pickMedia = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia() // Pick multiple!
                ) { uris ->
                    selectedUris = uris
                }
                var imageInferenceResult by remember { mutableStateOf("Waiting for image...") }
                var textInferenceResult by remember { mutableStateOf("Initializing...") }

                // 1. Setup the Photo Picker Launcher
                val pickMedia1 = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        imageInferenceResult = "Processing image..."
                        // Launch a coroutine so we don't block the UI thread during inference
                        lifecycleScope.launch(Dispatchers.Default) {
                            val bitmap = uriToBitmap(this@ModelActivity, uri)
                            imageInferenceResult = if (bitmap != null) {
                                try {
                                    runImageInference(bitmap)
                                } finally {
                                    bitmap.recycle()
                                }
                            } else {
                                "Error: Could not decode selected image."
                            }
                        }
                    } else {
                        imageInferenceResult = "No image selected."
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.Default) {
                        textInferenceResult = runTextInference()
                    }
                }

                Scaffold(modifier = Modifier) { innerPadding ->

                    Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {

                        Button(onClick = {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Text("Step A: Pick Images (${selectedUris.size})")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. The New Button
                        Button(
                            onClick = { triggerIndexing(true,selectedUris) },
                            enabled = selectedUris.isNotEmpty() // Only click if images are picked
                        ) {
                            Text("Step B: Start Indexing")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 4. Status Display
                        Text(text = currentStatus, style = MaterialTheme.typography.headlineSmall)
                        // 2. Add a Button to trigger the Photo Picker
                        Button(onClick = {
                            pickMedia1.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Text("Pick Image from Gallery")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(text = "Image Model Result:")
                        Text(text = imageInferenceResult, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(text = "Text Model Result:")
                        Text(text = textInferenceResult, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    // 3. Update the inference function to take a Bitmap
    private fun runImageInference(bitmap: Bitmap): String {
        return try {
            val finalImageEmbedding = EmbeddingModelFactory.createImageEmbedder(this).use { embedder ->
                embedder.getEmbedding(bitmap) { resized ->
                    saveBitmapForDebugging(this, resized)
                }
            }

            val preview = finalImageEmbedding.take(10).joinToString("\n")
            "Success! Embedding Size: ${finalImageEmbedding.size}\n\nFirst 10 values:\n$preview"

        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }

    private fun runTextInference(searchQuery: String = "a photo of a cute cat"): String {
        return try {
            // 1. Initialize our embedder and get the normalized FloatArray back!
            val finalTextEmbedding = EmbeddingModelFactory.createTextEmbedder(this).use { textEmbedder ->
                textEmbedder.getEmbedding(searchQuery)
            }

            // 4. Format the output for the UI
            val preview = finalTextEmbedding.take(5).joinToString(", ")
            "Success! Embedding Dim: ${finalTextEmbedding.size}\nPrompt: \"$searchQuery\"\nFirst 5 values: $preview"

        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }


    fun saveBitmapForDebugging(context: Context, bitmap: Bitmap, filename: String = "debug_squashed.png") {
        // Save to the app's external files directory so you can easily pull it
        val file = File(context.getExternalFilesDir(null), filename)

        try {
            FileOutputStream(file).use { out ->
                // CRITICAL: Must be PNG for lossless saving!
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d("ModelActivity", "Debug image saved successfully to: ${file.absolutePath}")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ModelActivity", "Failed to save debug image: ${e.message}")
        }
    }
}