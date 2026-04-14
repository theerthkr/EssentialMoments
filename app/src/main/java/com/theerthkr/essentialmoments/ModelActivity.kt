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

// Essential LiteRT 2.1.0 Imports
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EssentialMomentsTheme {
                var imageInferenceResult by remember { mutableStateOf("Waiting for image...") }
                var textInferenceResult by remember { mutableStateOf("Initializing...") }

                // 1. Setup the Photo Picker Launcher
                val pickMedia = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        imageInferenceResult = "Processing image..."
                        // Launch a coroutine so we don't block the UI thread during inference
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

                        // 2. Add a Button to trigger the Photo Picker
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

    // 3. Update the inference function to take a Bitmap
    private fun runImageInference(bitmap: Bitmap): String {
        return try {
            val model = CompiledModel.create(
                assets,
                "siglip2_base_patch16-224.tflite",
                CompiledModel.Options(Accelerator.NPU)
            )

            // Use the processor class we built previously!
            val processor = SiglipImageProcessor()

            // Run the pipeline up to the NCHW FloatArray step
            val resized = processor.resize(bitmap, 224, 224)
            saveBitmapForDebugging(this, resized) // 'this' is the Activity context
            val hwcPixels = processor.extractHwcPixels(resized)
            val rescaled = processor.rescale(hwcPixels, 255.0f)
            // Note: If you made siglipMean/siglipStd private in the class, just pass floatArrayOf(0.5f, 0.5f, 0.5f) here
            val normalized = processor.normalize(rescaled, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))

            // This is the final float array in [1, 3, 224, 224] format
            val nchwFloatArray = processor.transposeHwcToNchw(normalized, 224, 224)

            val inputBuffers = model.createInputBuffers()
            val outputBuffers = model.createOutputBuffers()

            // Write the processed FloatArray directly to the input buffer
            inputBuffers[0].writeFloat(nchwFloatArray)
            model.run(inputBuffers, outputBuffers)

            // Read the raw output
            val rawOutput = outputBuffers[0].readFloat()

// Normalize it for Semantic Search!
            val finalImageEmbedding = l2Normalize(rawOutput)

            val preview = finalImageEmbedding.take(10).joinToString("\n")
            "Success! Embedding Size: ${finalImageEmbedding.size}\n\nFirst 10 values:\n$preview"

        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }

    private fun runTextInference(searchQuery: String = "a photo of a cute cat"): String {
        return try {
            // 1. Initialize our new custom processor (passing 'this' for the Context)
            val textProcessor = SiglipTextProcessor(this)

            // 2. Pass the text and get the fully normalized 768-D FloatArray back!
            val finalTextEmbedding = textProcessor.getEmbedding(searchQuery)

            // 3. Free up memory
            textProcessor.close()

            // 4. Format the output for the UI
            val preview = finalTextEmbedding.take(5).joinToString(", ")
            "Success! Embedding Dim: ${finalTextEmbedding.size}\nPrompt: \"$searchQuery\"\nFirst 5 values: $preview"

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

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var sumOfSquares = 0.0f
        for (value in embedding) {
            sumOfSquares += value * value
        }

        val magnitude = kotlin.math.sqrt(sumOfSquares)

        // Safety check to avoid division by zero
        if (magnitude == 0.0f) return embedding

        val normalized = FloatArray(embedding.size)
        for (i in embedding.indices) {
            normalized[i] = embedding[i] / magnitude
        }
        return normalized
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