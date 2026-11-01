package com.theerthkr.essentialmoments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.theerthkr.essentialmoments.media.mediaThumbnailRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import coil.compose.AsyncImage

/**
 * Pixel width of one cell in a [columns]-wide grid.
 *
 * Used to request a MediaStore thumbnail at (approximately) display size, so the
 * provider never hands back a full-resolution bitmap for a small cell.
 */
@Composable
fun rememberThumbnailSizePx(columns: Int): Int {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(columns, screenWidthDp, density.density) {
        with(density) { (screenWidthDp.dp.toPx() / columns).roundToInt() }
    }
}

@Composable
fun PhotoGrid(
    photos: List<MediaImage>,
    onPhotoClick: (MediaImage) -> Unit,
    minColumns: Int = 2,
    maxColumns: Int = 8,
    defaultColumns: Int = 4
) {
    // Fixed column count keeps grid cells stable while scrolling: no per-frame
    // recomposition, no relayout, and no Coil cache churn from cells being
    // recreated.
    val columnCount = defaultColumns.coerceIn(minColumns, maxColumns)
    val cellWidthPx = rememberThumbnailSizePx(columnCount)
    val context = LocalContext.current
    // Photos arrive newest-first, so grouping consecutive items by calendar day keeps
    // the original order while producing one full-width header per day.
    val sections = remember(photos) { photos.groupByDay() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        contentPadding = PaddingValues(1.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        sections.forEach { section ->
            item(
                key = "day-${section.day}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "day-header"
            ) {
                DayHeader(section.day)
            }
            items(
                items = section.photos,
                key = { it.uri },
                contentType = { "photo" }
            ) { photo ->
                // MediaStore thumbnail at cell size: the original full-resolution file is
                // never read or decoded for the grid, so fast scrolling cannot saturate
                // the CPU or storage.
                val imageRequest = remember(context, photo.uri, cellWidthPx) {
                    mediaThumbnailRequest(context, photo.uri, cellWidthPx)
                }
                AsyncImage(
                    model = imageRequest,
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
}

private data class PhotoDaySection(val day: LocalDate, val photos: List<MediaImage>)

/** Splits a date-sorted list into consecutive calendar days, preserving order. */
private fun List<MediaImage>.groupByDay(): List<PhotoDaySection> {
    val zone = ZoneId.systemDefault()
    val byDay = LinkedHashMap<LocalDate, MutableList<MediaImage>>()
    for (photo in this) {
        val safeEpoch = photo.dateTaken.coerceIn(-62135596800000L, 253402300799000L)
        val day = Instant.ofEpochMilli(safeEpoch).atZone(zone).toLocalDate()
        byDay.getOrPut(day) { mutableListOf() }.add(photo)
    }
    return byDay.map { (day, photos) -> PhotoDaySection(day, photos) }
}

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
    }
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun AlbumCard(album: Album, coverSizePx: Int, onClick: (Album) -> Unit) {
    val context = LocalContext.current
    val coverRequest = remember(context, album.coverUri, coverSizePx) {
        mediaThumbnailRequest(context, album.coverUri, coverSizePx)
    }
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
        .clickable { onClick(album) }
    ){
        AsyncImage(
            model = coverRequest,
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
    val coverSizePx = rememberThumbnailSizePx(3)
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(items = albums, key = { it.id }) { album ->
            AlbumCard(
                album = album,
                coverSizePx = coverSizePx,
                onClick = { onAlbumClick(album) }
            )
        }
    }
}
