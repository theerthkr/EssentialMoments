package com.theerthkr.essentialmoments

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theerthkr.essentialmoments.indexing.IndexingScheduler
import com.theerthkr.essentialmoments.permissions.AppPermissions
import com.theerthkr.essentialmoments.permissions.MediaAccess
import com.theerthkr.essentialmoments.ui.model.ModelSetupBanner
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EssentialMomentsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainGalleryScreen()
                }
            }
        }
    }

    @Composable
    fun MainGalleryScreen() {
        val context = LocalContext.current
        val activity = LocalActivity.current ?: (context as? Activity)
        val lifecycleOwner = LocalLifecycleOwner.current
        val app = context.applicationContext as App
        var showMenu by remember { mutableStateOf(false) }
        var mediaAccess by remember { mutableStateOf(AppPermissions.mediaAccess(context)) }
        var backgroundIndexingActive by remember {
            mutableStateOf(IndexingScheduler.isBackgroundIndexingActive(context))
        }
        var showBackgroundNudge by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableStateOf("Photos") }

        val viewModel: GalleryViewModel = viewModel()
        val albums by viewModel.albums.collectAsStateWithLifecycle()
        val allPhotos by viewModel.allImages.collectAsStateWithLifecycle()

        // Photo access can be full, selected-only (Android 14+), or denied.
        // Re-read the system state on every resume because it can change from
        // app info without recreating this activity.
        val mediaPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            mediaAccess = AppPermissions.mediaAccess(context)
            // Android stops showing the dialog after repeated denials. Without
            // this fallback the permission button would appear to do nothing.
            if (mediaAccess == MediaAccess.NONE && activity != null) {
                AppPermissions.openSettingsIfMediaRequestBlocked(activity)
            }
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            if (AppPermissions.notificationAccess(context)) {
                IndexingScheduler.setBackgroundIndexingEnabled(context, true)
                backgroundIndexingActive = true
                showBackgroundNudge = false
            } else {
                IndexingScheduler.setBackgroundIndexingEnabled(context, false)
                backgroundIndexingActive = false
                showBackgroundNudge = false
            }
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val newAccess = AppPermissions.mediaAccess(context)
                    val hadAccess = mediaAccess.canReadPhotos
                    mediaAccess = newAccess
                    if (newAccess.canReadPhotos) {
                        // Refresh even when the state stays PARTIAL: the user may
                        // have changed the selected photo set while away.
                        viewModel.fetchAlbums()
                        viewModel.fetchAllImages()
                    } else if (hadAccess) {
                        viewModel.clear()
                        // Not accessible is the same as not existing. Drop the
                        // embeddings now; a later scan rebuilds them when access
                        // is granted again.
                        app.applicationScope.launch { app.photoIndexer.clearIndex() }
                    }
                    IndexingScheduler.reconcile(context)
                    backgroundIndexingActive =
                        IndexingScheduler.isBackgroundIndexingActive(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(mediaAccess) {
            if (mediaAccess.canReadPhotos) {
                viewModel.fetchAlbums()
                viewModel.fetchAllImages()
                IndexingScheduler.reconcile(context)
                backgroundIndexingActive =
                    IndexingScheduler.isBackgroundIndexingActive(context)
            } else {
                viewModel.clear()
                app.applicationScope.launch { app.photoIndexer.clearIndex() }
            }
        }

        // Subtle, throttled reminder. "Enable" asks for notification access
        // directly; "Not now" and the close button dismiss this occurrence.
        LaunchedEffect(mediaAccess, backgroundIndexingActive) {
            if (mediaAccess.canReadPhotos &&
                !backgroundIndexingActive &&
                IndexingScheduler.shouldShowBackgroundIndexingNudge(context)
            ) {
                IndexingScheduler.markBackgroundIndexingNudgeShown(context)
                showBackgroundNudge = true
            }
        }

        val enableBackgroundIndexing = {
            when {
                !AppPermissions.notificationsEnabled(context) -> {
                    if (AppPermissions.canRequestNotificationPermission() &&
                        !AppPermissions.hasNotificationPermission(context)
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        IndexingScheduler.setBackgroundIndexingEnabled(context, true)
                        AppPermissions.openNotificationSettings(context)
                        showBackgroundNudge = false
                    }
                }
                else -> {
                    IndexingScheduler.setBackgroundIndexingEnabled(context, true)
                    backgroundIndexingActive =
                        IndexingScheduler.isBackgroundIndexingActive(context)
                    showBackgroundNudge = false
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize()
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
                                        text = { Text("Settings") },
                                        onClick = {
                                            showMenu = false
                                            val intent = Intent(context, SettingsActivity::class.java)
                                            context.startActivity(intent)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (mediaAccess == MediaAccess.PARTIAL) {
                        PartialPhotoAccessBanner(
                            onManage = {
                                mediaPermissionLauncher.launch(AppPermissions.mediaPermissions())
                            }
                        )
                    }

                    if (showBackgroundNudge) {
                        BackgroundIndexingNudge(
                            onEnable = enableBackgroundIndexing,
                            onDismiss = { showBackgroundNudge = false }
                        )
                    }

                    if (mediaAccess.canReadPhotos) {
                        ModelSetupBanner(
                            onClick = {
                                context.startActivity(
                                    Intent(context, ModelDownloadActivity::class.java)
                                )
                            }
                        )
                        if (selectedTab == "Photos") {
                            PhotoGrid(
                                photos = allPhotos,
                                onPhotoClick = { photo ->
                                    val intent = Intent(context, ImageViewActivity::class.java).apply {
                                        putExtra("IMAGE_URI", photo.uri)
                                        putExtra("IMAGE_NAME", photo.uri.substringAfterLast("/"))
                                    }
                                    context.startActivity(intent)
                                }
                            )
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
                        NoPhotoAccess(
                            supportsPartialAccess = Build.VERSION.SDK_INT >=
                                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                            onRequestAccess = {
                                mediaPermissionLauncher.launch(AppPermissions.mediaPermissions())
                            },
                            onOpenSettings = { AppPermissions.openAppSettings(context) }
                        )
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
                            onClick = {
                                selectedTab = "Photos"
                                // Pick up anything added while the Albums tab
                                // (or another app) was in front.
                                viewModel.fetchAllImages()
                            },
                            accentColor = MaterialTheme.colorScheme.primary
                        )
                        TabButton(
                            text = "Albums",
                            isSelected = selectedTab == "Albums",
                            onClick = {
                                selectedTab = "Albums"
                                viewModel.fetchAlbums()
                            },
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
                // Read the animation in the Draw phase so press feedback does
                // not recompose this composable on every animation frame.
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
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

@Composable
private fun PartialPhotoAccessBanner(onManage: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Selected photos only",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Only the photos you chose are shown and indexed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onManage) {
                Text("Manage")
            }
        }
    }
}

@Composable
private fun BackgroundIndexingNudge(
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NotificationsOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Background indexing is off",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = "New photos won't be searchable until it's on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp, end = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEnable) { Text("Enable") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun NoPhotoAccess(
    supportsPartialAccess: Boolean,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = if (supportsPartialAccess) "Choose Photos" else "Allow Photo Access",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(56.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRequestAccess
                )
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No photos to show yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (supportsPartialAccess) {
                "Allow photo access to build your private library. You can allow everything " +
                    "or choose just the photos you want to search."
            } else {
                "Allow photo access to build your private, searchable library on this device."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestAccess) {
            Text(if (supportsPartialAccess) "Choose Photos" else "Allow Photo Access")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenSettings) {
            Text("Open app settings")
        }
    }
}
