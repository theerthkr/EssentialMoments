package com.theerthkr.essentialmoments

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                var inferenceResult by remember { mutableStateOf("Initializing...") }

                // 2. Run the inference once when the component enters the composition
                LaunchedEffect(Unit) {
                    // Running in a background thread is better for performance
                    withContext(Dispatchers.Default) {
                        inferenceResult = runInference()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 3. Display the result in a Compose Text element
                    Text(
                        text = inferenceResult,
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
    private fun runInference(): String {
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
}

