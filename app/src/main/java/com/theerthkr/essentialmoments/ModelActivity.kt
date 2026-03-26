package com.theerthkr.essentialmoments

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Essential LiteRT 2.1.0 Imports
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EssentialMomentsTheme(){
                // 1. Create a state variable to hold the result
                var imageInferenceResult by remember { mutableStateOf("Initializing...") }
                var textInferenceResult by remember { mutableStateOf("Initializing...") }

                // 2. Run the inference once when the component enters the composition
                LaunchedEffect(Unit) {
                    // Running in a background thread is better for performance
                    withContext(Dispatchers.Default) {
                        imageInferenceResult = runImageInference()
                    }
                    withContext(Dispatchers.Default) {
                        textInferenceResult = runTextInference()
                    }
                }

                Scaffold(modifier = Modifier) { innerPadding ->
                    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        // 3. Display the result in a Compose Text element
                        Text(
                            text = imageInferenceResult,
                            modifier = Modifier
                                .padding(16.dp)
                        )
                        Text(
                            text = textInferenceResult,
                            modifier = Modifier
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
    private fun runImageInference(): String {
        return try {
            val model = CompiledModel.create(
                assets,
                "siglip2_base_patch16-224_f16.tflite",
                CompiledModel.Options(Accelerator.NPU)
            )

            val tensorBytes = assets.open("test_tensor.bin").readBytes()
            val floatArray = FloatArray(tensorBytes.size / 4)
            ByteBuffer.wrap(tensorBytes)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .get(floatArray)

            val inputBuffers = model.createInputBuffers()
            val outputBuffers = model.createOutputBuffers()

            inputBuffers[0].writeFloat(floatArray)
            model.run(inputBuffers, outputBuffers)

            val outputFloatArray = outputBuffers[0].readFloat()
            val preview = outputFloatArray.take(10).joinToString("\n")

            // Return the success message
            "Success! Embedding Size: ${outputFloatArray.size}\n\nFirst 10 values:\n$preview"

        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }

    private fun runTextInference(): String {
        return try {
            // 1. Initialize the CompiledModel (Modern LiteRT 2026 API)
            val model = CompiledModel.create(
                assets,
                "siglip2_text_only.tflite", // Ensure this matches your exported filename
                CompiledModel.Options(Accelerator.NPU) // Fallback to GPU/CPU is automatic
            )

            // 2. Read the binary file from assets
            val idBytes = assets.open("text_input_ids.bin").readBytes()

            // 3. Convert Bytes to IntArray
            // We wrap it in a ByteBuffer to ensure native byte order (Little Endian)
            val intBuffer = ByteBuffer.wrap(idBytes)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer()

            val intArray = IntArray(intBuffer.remaining())
            intBuffer.get(intArray)

            // 4. Prepare I/O Buffers
            val inputBuffers = model.createInputBuffers()
            val outputBuffers = model.createOutputBuffers()

            // 5. Write the IDs into the first input slot
            inputBuffers[0].writeInt(intArray)

            // 6. Run Inference
            model.run(inputBuffers, outputBuffers)

            // 7. Read the resulting 768-dim Embedding
            val embedding = outputBuffers[0].readFloat()
            val preview = embedding.take(5).joinToString(", ")

            "Success! Embedding Dim: ${embedding.size}\nFirst 5 values: $preview"

        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.localizedMessage}"
        }
    }
}

