package com.theerthkr.essentialmoments

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlin.math.roundToInt
import kotlin.jvm.java

// ... imports (Ensure you have android.content.Intent and androidx.compose.runtime.*)

class MainActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Good practice for modern apps
        setContent {
            EssentialMomentsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainGalleryScreen()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Composable
    fun MainGalleryScreen() {
        val DarkSurface = Color(0xFF121212)
        val context = LocalContext.current
        var showMenu by remember { mutableStateOf(false) }
        var hasPermission by remember { mutableStateOf(false) }

        val viewModel: GalleryViewModel = viewModel()
        val albums by viewModel.albums.collectAsStateWithLifecycle()

        // 1. Permission Launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasPermission = isGranted
            if (isGranted) viewModel.fetchAlbums()
        }

        // 2. Initial Permission Check
        LaunchedEffect(Unit) {
            permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }

        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

            // 3. THE GALLERY CONTENT (Drawn first, so it stays behind the top bar)
            if (hasPermission) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Assuming AlbumGrid is defined elsewhere in your project
                    AlbumGrid(albums = albums) { album ->
                        val intent = Intent(context, AlbumDetailActivity::class.java).apply {
                            putExtra("ALBUM_ID", album.id)
                            putExtra("ALBUM_NAME", album.name)
                        }
                        context.startActivity(intent)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Please grant access to photos", color = Color.Gray)
                }
            }

            // 4. THE OVERLAY TOP BAR (Drawn last to stay on top)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                DarkSurface.copy(alpha = 0.95f),
                                DarkSurface.copy(alpha = 0.7f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(0.1f))

                    // Search Bar
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(48.dp))
                            .border(1.dp, Color.Gray, RoundedCornerShape(48.dp))
                            .background(color = Color.DarkGray)
                            .clickable {
                                val intent = Intent(context, SearchActivity::class.java)
                                context.startActivity(intent)
                                (context as? Activity)?.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
                            }
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = Color.Gray)
                            Spacer(Modifier.width(8.dp))
                            Text("Search...", color = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.weight(0.1f))

                    // Three-Dots Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Options", tint = Color.White)
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Run Model") },
                                onClick = {
                                    showMenu = false
                                    val intent = Intent(context, ModelActivity::class.java)
                                    context.startActivity(intent)
                                    // Smooth transition matching your search bar
                                    (context as? Activity)?.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { showMenu = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

// com/theerthkr/essential_moments/RunModelActivity.kt
// com/theerthkr/essential_moments/MainActivity.kt