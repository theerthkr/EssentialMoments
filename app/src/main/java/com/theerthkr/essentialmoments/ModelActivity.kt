package com.theerthkr.essentialmoments

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelActivity : ComponentActivity() {

    private lateinit var imageEmbedder: ImageEmbedder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imageEmbedder = ImageEmbedder(this)

        setContent {
            EssentialMomentsTheme {
                ModelDebugScreen()
            }
        }
    }

    @Composable
    fun ModelDebugScreen() {
        val scope = rememberCoroutineScope()

        var testTensorResult   by remember { mutableStateOf("Not run") }
        var realImageResult    by remember { mutableStateOf("Pick an image to test") }
        var selfSimResult      by remember { mutableStateOf("") }
        var isLoading          by remember { mutableStateOf(false) }

        val pickImage = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.Default) {
                isLoading = true
                realImageResult = "Running pipeline..."

                imageEmbedder.initialize(debug = true)

                val embedding = imageEmbedder.embed(uri, debug = true)
                realImageResult = if (embedding != null) {
                    val norm = imageEmbedder.l2Norm(embedding)
                    "✅ dim=${embedding.size}  L2_norm=${"%.6f".format(norm)}\n" +
                            "first 5: ${embedding.take(5).joinToString { "%.4f".format(it) }}"
                } else {
                    "❌ Pipeline failed — check Logcat tag: ImageEmbedder / ImagePreprocessor"
                }

                // Self-similarity test
                val bmp = android.graphics.BitmapFactory.decodeStream(
                    contentResolver.openInputStream(uri)
                )
                if (bmp != null) {
                    val sim = imageEmbedder.selfSimilarityTest(bmp)
                    selfSimResult = "Self-similarity: ${"%.6f".format(sim)} (expect ≈ 1.0)"
                }

                isLoading = false
            }
        }

        // Original test_tensor.bin test (keep for regression)
        LaunchedEffect(Unit) {
            launch(Dispatchers.Default) {
                testTensorResult = runLegacyTestTensor()
            }
        }

        Scaffold { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Model Debug", style = MaterialTheme.typography.headlineSmall)

                // ── Legacy test_tensor.bin ──────────────────────────
                Text("Legacy test_tensor.bin:", style = MaterialTheme.typography.labelLarge)
                Text(testTensorResult, style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()

                // ── Real image pipeline ─────────────────────────────
                Text("Real image pipeline:", style = MaterialTheme.typography.labelLarge)

                Button(
                    onClick = { pickImage.launch("image/*") },
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Running…" else "Pick image & embed")
                }

                Text(
                    realImageResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (realImageResult.startsWith("✅"))
                        MaterialTheme.colorScheme.primary
                    else if (realImageResult.startsWith("❌"))
                        MaterialTheme.colorScheme.error
                    else Color.Unspecified
                )

                if (selfSimResult.isNotEmpty()) {
                    Text(
                        selfSimResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selfSimResult.contains("0.9999") || selfSimResult.contains("1.000"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    private fun runLegacyTestTensor(): String {
        return try {
            val model = CompiledModel.create(
                assets,
                "siglip2_base_patch16-224_f16.tflite",
                CompiledModel.Options(Accelerator.NPU)
            )
            val tensorBytes = assets.open("test_tensor.bin").readBytes()
            val floatArray = FloatArray(tensorBytes.size / 4)
            ByteBuffer.wrap(tensorBytes).order(ByteOrder.nativeOrder())
                .asFloatBuffer().get(floatArray)
            val inputBuffers  = model.createInputBuffers()
            val outputBuffers = model.createOutputBuffers()
            inputBuffers[0].writeFloat(floatArray)
            model.run(inputBuffers, outputBuffers)
            val out = outputBuffers[0].readFloat()
            "✅ dim=${out.size}  first3=${out.take(3).joinToString { "%.4f".format(it) }}"
        } catch (e: Exception) {
            "❌ ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageEmbedder.close()
    }
}