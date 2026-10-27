package com.theerthkr.essentialmoments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import coil.compose.AsyncImage

@Composable
fun PhotoGrid(
    photos: List<MediaImage>,
    onPhotoClick: (MediaImage) -> Unit,
    minColumns: Int = 2,
    maxColumns: Int = 8,
    defaultColumns: Int = 4
) {
    // Pinch-to-zoom changes the photo density: zoom in → fewer/larger photos per row;
    // zoom out → more/smaller photos per row (Apple Photos–like grid scaling).
    val zoomState = remember { mutableStateOf(1f) }
    val zoom = zoomState.value
    val columnCount = (defaultColumns / zoom).roundToInt().coerceIn(minColumns, maxColumns)

    // reverseLayout = true anchors the first item at the bottom, so the newest photos
    // (the list is newest-first from MediaCatalog/GalleryViewModel) appear at the bottom,
    // matching the Apple Photos library scroll direction.
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        contentPadding = PaddingValues(1.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomDelta, _ ->
                    // Only respond to pinches (zoomDelta). Single-finger pan is ignored so
                    // the grid can still scroll vertically. NOTE: on some devices the
                    // transform detector can compete with grid scrolling; if single-finger
                    // scroll feels blocked, gate the detector behind a "zoom mode" toggle.
                    if (zoomDelta != 0f) {
                        zoomState.value = (zoom * zoomDelta).coerceIn(0.5f, 2.0f)
                    }
                }
            },
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(photos) { photo ->
            AsyncImage(
                model = photo.uri,
                contentDescription = null,
                modifier = Modifier
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .clickable { onPhotoClick(photo) },
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun AlbumCard(album: Album, onClick: (Album) -> Unit){
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
        .clickable { onClick(album) }
    ){
        AsyncImage(
            model = album.coverUri,
            contentDescription = "Cover for ${album.name}",
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.DarkGray),
            contentScale = ContentScale.Crop
        )
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = album.name,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Serif
            ),
            color = Color.White,
            maxLines = 1
        )
        Text(
            text = album.photoCount.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(albums) { album ->
            AlbumCard(
                album = album,
                onClick = { onAlbumClick(album) }
            )
        }
    }
}