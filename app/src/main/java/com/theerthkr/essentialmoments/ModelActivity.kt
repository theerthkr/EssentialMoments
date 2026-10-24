package com.theerthkr.essentialmoments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.ml.TextEmbedder
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EssentialMomentsTheme {
                var imageInferenceResult by remember { mutableStateOf("Waiting for image...") }
                var textInferenceResult by remember { mutableStateOf("Initializing...") }

                val pickMedia = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        imageInferenceResult = "Processing image..."
                        lifecycleScope.launch(Dispatchers.Default) {
                            val bitmap = uriToBitmap(uri)
                            imageInferenceResult = runImageInference(bitmap)
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

    private fun runImageInference(bitmap: Bitmap): String {
        return try {
            val embedder = ImageEmbedder(this)
            embedder.initialize()
            val embedding = embedder.embedBitmap(bitmap)
            embedder.close()

            if (embedding != null) {
                val preview = embedding.take(10).joinToString("\n")
                "Success! Embedding Size: ${embedding.size}\n\nFirst 10 values:\n$preview"
            } else {
                "Error generating image embedding"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }

    private fun runTextInference(searchQuery: String = "a photo of a cute cat"): String {
        return try {
            val textEmbedder = TextEmbedder(this)
            textEmbedder.initialize()
            val finalTextEmbedding = textEmbedder.embed(searchQuery)
            textEmbedder.close()

            if (finalTextEmbedding != null) {
                val preview = finalTextEmbedding.take(5).joinToString(", ")
                "Success! Embedding Dim: ${finalTextEmbedding.size}\nPrompt: \"$searchQuery\"\nFirst 5 values: $preview"
            } else {
                "Error generating text embedding"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }
}
