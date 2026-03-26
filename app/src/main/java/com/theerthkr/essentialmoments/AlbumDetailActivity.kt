package com.theerthkr.essentialmoments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButtonDefaults.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import com.theerthkr.essentialmoments.GalleryViewModel
import kotlin.jvm.java

class AlbumDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pull the "extras" from the intent
        val albumId = intent.getStringExtra("ALBUM_ID") ?: ""
        val albumName = intent.getStringExtra("ALBUM_NAME") ?: "Photos"

        setContent {

            EssentialMomentsTheme() {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AlbumDetailScreen(albumId, albumName)
                }
            }
        }
    }

    @Composable
    fun AlbumDetailScreen(
        albumId: String,
        albumName: String,
        viewModel: GalleryViewModel = viewModel()
    ) {
        val context = LocalContext.current      // 1. Tell the ViewModel to fetch images for this specific album
        LaunchedEffect(albumId) {
            viewModel.fetchImagesForAlbum(albumId)
        }

        // 2. Observe the images list from the ViewModel
        val images by viewModel.images.collectAsStateWithLifecycle()

        // 3. Layout the UI (Top Bar + Grid)
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. The Image Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3), // 3 photos per row
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 90.dp)
            ) {
                items(images) { image ->
                    AsyncImage(
                        model = image.uri,
                        contentDescription = null,
                        modifier = Modifier
                            .aspectRatio(1f) // Makes it square
                            .padding(1.dp)
//                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                val intent = Intent(context, ImageViewActivity::class.java).apply {
                                    putExtra("IMAGE_URI", image.uri)
                                    // We'll extract the filename from the path
                                    val fileName = image.uri.substringAfterLast("/")
                                    putExtra("IMAGE_NAME", fileName)
                                }
                                context.startActivity(intent)

                            },

                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 2. Re-using your Top Bar logic (Manual Render as you requested)
            DetailTopBar(
                title = albumName,
                onBack = {
                    // Cast context to Activity only when the button is actually clicked
                    (context as? Activity)?.finish()
                }
            )
        }
    }

    @Composable
    fun DetailTopBar(title: String, onBack: () -> Unit) {
        // 1. Get the context and cast it safely to Activity
        val context = LocalContext.current
        val activity = context as? Activity

        val DarkSurface = Color(0xFF121212)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            DarkSurface.copy(alpha = 0.9f),
                            DarkSurface.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
                .statusBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    color = Color.White
                )

                IconButton(onClick = {
                    val intent = Intent(context, SearchActivity::class.java)
                    context.startActivity(intent)

                }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White,


                        )

                }

                IconButton(onClick = { /* Options logic */ }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

