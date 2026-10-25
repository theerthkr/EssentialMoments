package com.theerthkr.essentialmoments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageInfoBottomSheet(
    info: ImageInfo,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Black,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.DarkGray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header: Date and Name
            Text(
                text = info.date,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info.name,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Image Name", info.name)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp).padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy name",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "No location information",
                color = Color.LightGray,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.DarkGray)
            Spacer(modifier = Modifier.height(24.dp))

            // Camera info
            if (info.make != null || info.model != null) {
                Text(
                    text = listOfNotNull(info.make, info.model).joinToString(" "),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            } else {
                Text(
                    text = "Unknown Device",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val details = mutableListOf<String>()
            if (info.megapixels.isNotEmpty()) details.add(info.megapixels)
            if (info.dimensions.isNotEmpty()) details.add(info.dimensions)
            if (info.sizeStr.isNotEmpty()) details.add(info.sizeStr)
            if (info.mimeType.isNotEmpty()) details.add(info.mimeType)

            Text(
                text = details.joinToString(" | "),
                color = Color.LightGray,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Camera settings pill
            val hasSettings = info.iso != null || info.exposureTime != null || info.aperture != null
            if (hasSettings) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.DarkGray.copy(alpha = 0.5f))
                        .padding(vertical = 16.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CameraSettingItem("ISO", info.iso ?: "-")
                    Text("|", color = Color.Gray)
                    CameraSettingItem("-", "-") // focal length not parsed yet
                    Text("|", color = Color.Gray)
                    CameraSettingItem("", info.exposureTime ?: "-")
                    Text("|", color = Color.Gray)
                    CameraSettingItem("", info.aperture ?: "-")
                    Text("|", color = Color.Gray)
                    CameraSettingItem("-", "-")
                }
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (info.path != null) {
                // Folder path
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = extractFolderPath(info.path),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(">", color = Color.Gray)
                }

                HorizontalDivider(color = Color.DarkGray)

                // Album name
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoAlbum,
                        contentDescription = "Album",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "From \"${extractAlbumName(info.path)}\" album",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(">", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun CameraSettingItem(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun extractFolderPath(path: String): String {
    val parts = path.split("/")
    if (parts.size > 2) {
        val folderParts = parts.dropLast(1)
        val visiblePath = folderParts.takeLast(3).joinToString("/")
        return "$visiblePath/"
    }
    return path
}

private fun extractAlbumName(path: String): String {
    val parts = path.split("/")
    if (parts.size > 1) {
        return parts[parts.size - 2]
    }
    return "Unknown"
}
