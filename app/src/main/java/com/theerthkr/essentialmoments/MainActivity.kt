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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButtonDefaults
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
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        var showMenu by remember { mutableStateOf(false) }
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, permissions) == PackageManager.PERMISSION_GRANTED
            )
        }
        var selectedTab by remember { mutableStateOf("Photos") }

        val viewModel: GalleryViewModel = viewModel()
        val albums by viewModel.albums.collectAsStateWithLifecycle()
        val allPhotos by viewModel.allImages.collectAsStateWithLifecycle()
        val isDescending by viewModel.isDescending.collectAsStateWithLifecycle()

        // 1. Permission Launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasPermission = isGranted
            if (isGranted) {
                viewModel.fetchAlbums()
                viewModel.fetchAllImages()
            }
        }

        // 2. Initial Permission Check
        LaunchedEffect(hasPermission) {
            if (hasPermission) {
                viewModel.fetchAlbums()
                viewModel.fetchAllImages()
            } else {
                permissionLauncher.launch(permissions)
            }
        }

        val searchVm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(context))
        val imagePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (uris.isNotEmpty()) {
                searchVm.indexImages(uris)
                val intent = Intent(context, SearchActivity::class.java)
                context.startActivity(intent)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                // FAB with Glow Effect
                Box(
                    modifier = Modifier
                        .padding(bottom = 80.dp)
                ) {
                    // Glow background
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                            .padding(4.dp)
                    )

                    FloatingActionButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (selectedTab == "Photos") {
                                viewModel.toggleSortOrder()
                            } else {
                                imagePicker.launch("image/*")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selectedTab == "Photos" && isDescending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape)
                    ) {
                        if (selectedTab == "Photos") {
                            Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
                        } else {
                            Icon(Icons.Default.Add, "Index Photos", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
            ) {
                // Main Content
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top Bar with Title and Icons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = selectedTab,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row {
                            IconButton(onClick = {
                                val intent = Intent(context, SearchActivity::class.java)
                                context.startActivity(intent)
                            }) {
                                Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
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
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (hasPermission) {
                        if (selectedTab == "Photos") {
                            PhotoGrid(photos = allPhotos)
                        } else {
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
                            Text("Please grant access to photos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Floating Bottom Navigation (Clean Solid Style)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(50.dp))
                        .padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TabButton(
                            text = "Photos",
                            isSelected = selectedTab == "Photos",
                            onClick = { selectedTab = "Photos" },
                            accentColor = MaterialTheme.colorScheme.primary
                        )
                        TabButton(
                            text = "Albums",
                            isSelected = selectedTab == "Albums",
                            onClick = { selectedTab = "Albums" },
                            accentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun TabButton(text: String, isSelected: Boolean, onClick: () -> Unit, accentColor: Color) {
        val haptic = LocalHapticFeedback.current
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(if (isPressed) 0.92f else 1f, label = "scale")

        Box(
            modifier = Modifier
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(50.dp))
                .background(if (isSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// com/theerthkr/essential_moments/RunModelActivity.kt
// com/theerthkr/essential_moments/MainActivity.kt