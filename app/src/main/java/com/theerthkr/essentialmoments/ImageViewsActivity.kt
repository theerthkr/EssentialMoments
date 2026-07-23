package com.theerthkr.essentialmoments

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
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
    var showInfoSheet by remember { mutableStateOf(false) }
    var imageInfo by remember { mutableStateOf<ImageInfo?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ZoomableImage(uri)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.4f))
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

            IconButton(onClick = {
                if (imageInfo == null) {
                    imageInfo = getImageInfo(context, uri)
                }
                showInfoSheet = true
            }) {
                Icon(Icons.Default.Info, "Info", tint = Color.White)
            }

            IconButton(onClick = { /* Nothing yet */ }) {
                Icon(Icons.Default.MoreVert, "Options", tint = Color.White)
            }
        }
    }

    if (showInfoSheet) {
        imageInfo?.let { info ->
            ImageInfoBottomSheet(
                info = info,
                onDismissRequest = { showInfoSheet = false }
            )
        }
    }
}

@Composable
fun ZoomableImage(uri: String) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RectangleShape)
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale *= zoom
                    scale = scale.coerceIn(1f, 5f)

                    if (scale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
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
