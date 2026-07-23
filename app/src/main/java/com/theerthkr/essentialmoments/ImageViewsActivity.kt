package com.theerthkr.essentialmoments

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.filled.Phone
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePagerScreen(uri: String, name: String) {
    val context = LocalActivity.current as Activity
    var showInfoSheet by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf<ImageMetadata?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val md = getImageMetadata(context, uri)
            metadata = md
        }
    }

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

            IconButton(onClick = { showInfoSheet = true }) {
                Icon(Icons.Default.Info, "Info", tint = Color.White)
            }
        }
    }

    if (showInfoSheet && metadata != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White
        ) {
            InfoSheetContent(metadata!!, context)
        }
    }
}

@Composable
fun InfoSheetContent(metadata: ImageMetadata, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        if (metadata.date.isNotEmpty()) {
            Text(
                text = metadata.date,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = metadata.name,
                fontSize = 14.sp,
                color = Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(
                onClick = {
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Image Name", metadata.name)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp).padding(start = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy Name",
                    tint = Color.LightGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "No location information",
            fontSize = 14.sp,
            color = Color.LightGray
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(modifier = Modifier.height(24.dp))

        if (metadata.make.isNotEmpty() || metadata.model.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Phone,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "${metadata.make} ${metadata.model}".trim(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val mp = if (metadata.width > 0 && metadata.height > 0) {
                        val pixels = metadata.width * metadata.height
                        "${(pixels / 1000000.0).toInt()}MP"
                    } else ""

                    val res = if (metadata.width > 0 && metadata.height > 0) "${metadata.width} × ${metadata.height}" else ""
                    val size = formatSize(metadata.sizeBytes)
                    val format = if (metadata.name.contains(".")) metadata.name.substringAfterLast(".").uppercase() else ""

                    val details = listOf(mp, res, size, format).filter { it.isNotEmpty() }.joinToString(" | ")
                    Text(
                        text = details,
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Camera settings row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2C2C2E))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraSettingItem("ISO", metadata.iso.ifEmpty { "-" })
                Text("|", color = Color.Gray)
                CameraSettingItem("", "${metadata.focalLength} mm".replace(" mm", "mm").takeIf { metadata.focalLength.isNotEmpty() } ?: "-")
                Text("|", color = Color.Gray)
                CameraSettingItem("", metadata.exposureTime.ifEmpty { "-" } + if (metadata.exposureTime.isNotEmpty()) " s" else "")
                Text("|", color = Color.Gray)
                CameraSettingItem("", if (metadata.aperture.isNotEmpty()) "f ${metadata.aperture}" else "-")
                Text("|", color = Color.Gray)
                CameraSettingItem("", "-") // EV compensation or similar, leaving as - for now
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.DarkGray)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Folder Path
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = metadata.path.ifEmpty { "Unknown path" },
                fontSize = 16.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = Color.DarkGray)
        Spacer(modifier = Modifier.height(24.dp))

        // Album (Mocked as Camera for now, or derive from path)
        val albumName = if (metadata.path.contains("/")) {
            val parts = metadata.path.split("/")
            if (parts.size >= 2) parts[parts.size - 2] else "Camera"
        } else {
            "Camera"
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.PhotoAlbum,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "From \"$albumName\" album",
                fontSize = 16.sp,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun CameraSettingItem(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
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
