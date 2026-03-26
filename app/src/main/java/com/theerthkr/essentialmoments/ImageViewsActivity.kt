package com.theerthkr.essentialmoments

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButtonDefaults.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import java.time.Clock.offset
import androidx.activity.compose.LocalActivity

class ImageViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra("IMAGE_URI") ?: ""
        val name = intent.getStringExtra("IMAGE_NAME") ?: "Image"

        setContent {
            EssentialMomentsTheme {
                Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
                    ImagePagerScreen(uri, name)
                }
            }
        }
    }
}

@Composable
fun ImagePagerScreen(uri: String, name: String) {
    val context = LocalActivity.current as Activity

    Box(modifier = Modifier
        .fillMaxSize()) {
        // 1. The Main Image (Centered)
        ZoomableImage(uri)

        // 2. The Minimal Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.4f)) // Slight dark overlay for readability
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { context.finish() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }

            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            IconButton(onClick = { /* Nothing yet */ }) {
                Icon(Icons.Default.MoreVert, "Options", tint = Color.White)
            }
        }
    }
}

@Composable
fun ZoomableImage(uri: String) {
    // 1. State to keep track of the transformations
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RectangleShape) // Keeps the image from "bleeding" over other UI when zoomed
            .background(Color.Black)
            .pointerInput(Unit) {
                // 2. Detect gestures (pinch to zoom, pan to move)
                detectTransformGestures { _, pan, zoom, _ ->
                    scale *= zoom
                    // Limit the scale so users don't zoom into infinity
                    scale = scale.coerceIn(1f, 5f)

                    // Only allow panning (moving) if we are zoomed in
                    if (scale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero // Reset if zoomed out
                    }
                }
            }
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit
        )
    }
}