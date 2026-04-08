package com.theerthkr.essentialmoments

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.ml.TextEmbedder
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

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
        val scope   = rememberCoroutineScope()
        val context = LocalContext.current
        val scroll  = rememberScrollState()

        // ── State ────────────────────────────────────────────────
        var legacyResult    by remember { mutableStateOf("Tap 'Run legacy test'") }
        var imageResult     by remember { mutableStateOf("Pick an image to test") }
        var selfSimResult   by remember { mutableStateOf("") }
        var crossSimResult  by remember { mutableStateOf("") }
        var textEmbedResult by remember { mutableStateOf("Not run") }
        var isLoading       by remember { mutableStateOf(false) }

        // For cross-similarity: hold two URIs
        var uriA by remember { mutableStateOf<Uri?>(null) }
        var uriB by remember { mutableStateOf<Uri?>(null) }

        val textEmbedder = remember { TextEmbedder(context) }

        // ── Image pickers ────────────────────────────────────────
        val pickImageA = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uriA = uri
            crossSimResult = if (uriB != null) "Ready — tap Cross-similarity" else "Now pick image B"
        }

        val pickImageB = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uriB = uri
            crossSimResult = if (uriA != null) "Ready — tap Cross-similarity" else "Pick image A first"
        }

        val pickSingleImage = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.Default) {
                isLoading = true
                imageResult   = "Running pipeline…"
                selfSimResult = ""

                imageEmbedder.initialize(debug = true)

                val embedding = imageEmbedder.embed(uri, debug = true)
                imageResult = if (embedding != null) {
                    val norm = imageEmbedder.l2Norm(embedding)
                    "✅ dim=${embedding.size}  L2=${"%.6f".format(norm)}\n" +
                    "first 5: ${embedding.take(5).joinToString { "%.4f".format(it) }}\n" +
                    "delegate: ${imageEmbedder.activeDelegate}"
                } else {
                    "❌ Pipeline failed — check Logcat tags: ImageEmbedder / ImagePreprocessor"
                }

                // Self-similarity test
                val bmp = android.graphics.BitmapFactory.decodeStream(
                    contentResolver.openInputStream(uri)
                )
                if (bmp != null) {
                    val sim = imageEmbedder.selfSimilarityTest(bmp)
                    selfSimResult = "Self-similarity: ${"%.6f".format(sim)}  (expect ≈ 1.0)"
                    bmp.recycle()
                }

                isLoading = false
            }
        }

        // ── UI ───────────────────────────────────────────────────
        Scaffold { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Model Debug", style = MaterialTheme.typography.headlineSmall)

                // ── 1. Legacy test_tensor.bin ─────────────────────
                SectionLabel("1 · Legacy test_tensor.bin")
                Button(onClick = {
                    scope.launch(Dispatchers.Default) {
                        legacyResult = "Running…"
                        legacyResult = runLegacyTestTensor()
                    }
                }) { Text("Run legacy test") }
                ResultText(legacyResult)

                HorizontalDivider()

                // ── 2. Real image — full pipeline ─────────────────
                SectionLabel("2 · Full image pipeline (all debug checkpoints)")
                Button(
                    onClick  = { pickSingleImage.launch("image/*") },
                    enabled  = !isLoading
                ) { Text(if (isLoading) "Running…" else "Pick image & embed") }

                ResultText(imageResult)
                if (selfSimResult.isNotEmpty()) ResultText(selfSimResult)

                HorizontalDivider()

                // ── 3. Cross-similarity ───────────────────────────
                SectionLabel("3 · Cross-similarity (two different images)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickImageA.launch("image/*") }) {
                        Text(if (uriA == null) "Pick A" else "A ✅")
                    }
                    Button(onClick = { pickImageB.launch("image/*") }) {
                        Text(if (uriB == null) "Pick B" else "B ✅")
                    }
                }
                Button(
                    onClick = {
                        val a = uriA ?: return@Button
                        val b = uriB ?: return@Button
                        scope.launch(Dispatchers.Default) {
                            isLoading = true
                            crossSimResult = "Computing…"
                            imageEmbedder.initialize()
                            val sim = imageEmbedder.crossSimilarityTest(a, b)
                            crossSimResult = if (sim >= 0f) {
                                "Cosine similarity: ${"%.4f".format(sim)}\n" +
                                "similar ≈ 0.9+  |  unlike ≈ 0.5–0.7"
                            } else {
                                "❌ Cross-similarity failed"
                            }
                            isLoading = false
                        }
                    },
                    enabled = uriA != null && uriB != null && !isLoading
                ) { Text("Run cross-similarity") }
                if (crossSimResult.isNotEmpty()) ResultText(crossSimResult)

                HorizontalDivider()

                // ── 4. Text embedding ─────────────────────────────
                SectionLabel("4 · Text embedding")
                Button(onClick = {
                    scope.launch(Dispatchers.Default) {
                        textEmbedResult = "Running…"
                        textEmbedder.initialize(debug = true)
                        val e = textEmbedder.embed("a cat sitting on a sofa", debug = true)
                        textEmbedResult = if (e != null) {
                            val norm = sqrt(e.fold(0f) { a, x -> a + x * x })
                            "✅ dim=${e.size}  L2=${"%.6f".format(norm)}\n" +
                            "first 5: ${e.take(5).joinToString { "%.4f".format(it) }}"
                        } else {
                            "❌ Text embed failed — check Logcat: TextEmbedder / SigLIPTokenizer"
                        }
                    }
                }) { Text("Test text embed") }
                ResultText(textEmbedResult)
            }
        }
    }

    // ── Composable helpers ────────────────────────────────────────

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

    @Composable
    private fun ResultText(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                text.startsWith("✅") -> MaterialTheme.colorScheme.primary
                text.startsWith("❌") -> MaterialTheme.colorScheme.error
                else -> Color.Unspecified
            }
        )
    }

    // ── Legacy test (kept for regression) ─────────────────────────

    private fun runLegacyTestTensor(): String {
        return try {
            val model = CompiledModel.create(
                assets,
                "siglip2_base_patch16-224_f16.tflite",
                CompiledModel.Options(Accelerator.NPU)
            )
            val tensorBytes = assets.open("test_tensor.bin").readBytes()
            val floatArray  = FloatArray(tensorBytes.size / 4)
            ByteBuffer.wrap(tensorBytes)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .get(floatArray)

            val inputBuffers  = model.createInputBuffers()
            val outputBuffers = model.createOutputBuffers()
            inputBuffers[0].writeFloat(floatArray)
            model.run(inputBuffers, outputBuffers)
            val out = outputBuffers[0].readFloat()

            val norm = sqrt(out.fold(0f) { a, x -> a + x * x })
            "✅ dim=${out.size}  L2=${"%.6f".format(norm)}\n" +
            "first 3: ${out.take(3).joinToString { "%.4f".format(it) }}"
        } catch (e: Exception) {
            "❌ ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageEmbedder.close()
    }
}
