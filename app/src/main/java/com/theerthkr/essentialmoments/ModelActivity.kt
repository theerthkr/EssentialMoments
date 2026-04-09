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
import com.theerthkr.essentialmoments.ml.SigLIPTokenizer
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
        setContent { EssentialMomentsTheme { ModelDebugScreen() } }
    }

    @Composable
    fun ModelDebugScreen() {
        val scope   = rememberCoroutineScope()
        val context = LocalContext.current
        val scroll  = rememberScrollState()

        var legacyResult    by remember { mutableStateOf("Tap run") }
        var imageResult     by remember { mutableStateOf("Pick an image") }
        var selfSimResult   by remember { mutableStateOf("") }
        var crossSimResult  by remember { mutableStateOf("") }
        var textEmbedResult by remember { mutableStateOf("Not run") }
        var tokenizerResult by remember { mutableStateOf("Not run") }
        var isLoading       by remember { mutableStateOf(false) }
        var uriA by remember { mutableStateOf<Uri?>(null) }
        var uriB by remember { mutableStateOf<Uri?>(null) }

        val textEmbedder = remember { TextEmbedder(context) }
        val tokenizer    = remember { SigLIPTokenizer(context) }

        val pickSingle = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.Default) {
                isLoading = true; imageResult = "Running..."; selfSimResult = ""
                imageEmbedder.initialize(debug = true)
                val e = imageEmbedder.embed(uri, debug = true)
                imageResult = if (e != null)
                    "OK dim=${e.size} L2=${"%.6f".format(imageEmbedder.l2Norm(e))}\nfirst5: ${e.take(5).joinToString { "%.4f".format(it) }}\ndelegate: ${imageEmbedder.activeDelegate}"
                else "FAIL - check Logcat: ImageEmbedder"
                val bmp = android.graphics.BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                if (bmp != null) {
                    val sim = imageEmbedder.selfSimilarityTest(bmp)
                    selfSimResult = "SelfSim: ${"%.6f".format(sim)} (expect ~1.0)"
                    bmp.recycle()
                }
                isLoading = false
            }
        }
        val pickA = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uriA = it; crossSimResult = if (uriB != null) "Ready" else "Pick B" }
        val pickB = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uriB = it; crossSimResult = if (uriA != null) "Ready" else "Pick A" }

        Scaffold { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Text("Model Debug", style = MaterialTheme.typography.headlineSmall)

                Text("1 - Legacy tensor", style = MaterialTheme.typography.labelLarge)
                Button(onClick = { scope.launch(Dispatchers.Default) { legacyResult = "..."; legacyResult = runLegacy() } }) { Text("Run legacy") }
                RText(legacyResult); HorizontalDivider()

                Text("2 - Image pipeline", style = MaterialTheme.typography.labelLarge)
                Button(onClick = { pickSingle.launch("image/*") }, enabled = !isLoading) { Text(if (isLoading) "Running..." else "Pick & embed") }
                RText(imageResult)
                if (selfSimResult.isNotEmpty()) RText(selfSimResult)
                HorizontalDivider()

                Text("3 - Cross-similarity", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickA.launch("image/*") }) { Text(if (uriA == null) "Pick A" else "A OK") }
                    Button(onClick = { pickB.launch("image/*") }) { Text(if (uriB == null) "Pick B" else "B OK") }
                }
                Button(onClick = {
                    val a = uriA ?: return@Button; val b = uriB ?: return@Button
                    scope.launch(Dispatchers.Default) {
                        isLoading = true; crossSimResult = "..."
                        imageEmbedder.initialize()
                        val sim = imageEmbedder.crossSimilarityTest(a, b)
                        crossSimResult = if (sim >= 0f) "Cosine: ${"%.4f".format(sim)}\nsimilar~0.9+ | unlike~0.5-0.7" else "FAIL"
                        isLoading = false
                    }
                }, enabled = uriA != null && uriB != null && !isLoading) { Text("Run cross-sim") }
                if (crossSimResult.isNotEmpty()) RText(crossSimResult)
                HorizontalDivider()

                Text("4 - Tokenizer + text embed", style = MaterialTheme.typography.labelLarge)
                Button(onClick = {
                    scope.launch(Dispatchers.Default) {
                        tokenizer.initialize()
                        // Each phrase should produce 3-8 pieces, not 1 giant or char-by-char
                        tokenizerResult = listOf(
                            "a cat sitting on a sofa",
                            "sunset at the beach",
                            "birthday party"
                        ).joinToString("\n") { tokenizer.debugTokenize(it) }
                    }
                }) { Text("Test tokenizer") }
                RText(tokenizerResult)

                Button(onClick = {
                    scope.launch(Dispatchers.Default) {
                        textEmbedResult = "..."
                        textEmbedder.initialize(debug = true)
                        val e = textEmbedder.embed("a cat sitting on a sofa", debug = true)
                        textEmbedResult = if (e != null) {
                            val n = sqrt(e.fold(0f) { a, x -> a + x * x })
                            "OK dim=${e.size} L2=${"%.6f".format(n)}\nfirst5: ${e.take(5).joinToString { "%.4f".format(it) }}"
                        } else "FAIL - check Logcat: TextEmbedder"
                    }
                }) { Text("Test text embed") }
                RText(textEmbedResult)
            }
        }
    }

    @Composable private fun RText(t: String) = Text(t,
        style = MaterialTheme.typography.bodySmall,
        color = when { t.startsWith("OK") || t.startsWith("Self") -> MaterialTheme.colorScheme.primary
            t.startsWith("FAIL") -> MaterialTheme.colorScheme.error
            else -> Color.Unspecified })

    private fun runLegacy(): String = try {
        val m = CompiledModel.create(assets, "siglip2_base_patch16-224_f16.tflite", CompiledModel.Options(Accelerator.NPU))
        val b = assets.open("test_tensor.bin").readBytes()
        val f = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
        val ib = m.createInputBuffers(); val ob = m.createOutputBuffers()
        ib[0].writeFloat(f); m.run(ib, ob); val out = ob[0].readFloat()
        "OK dim=${out.size} L2=${"%.4f".format(sqrt(out.fold(0f){a,x->a+x*x}))}"
    } catch (e: Exception) { "FAIL: ${e.message}" }

   }
