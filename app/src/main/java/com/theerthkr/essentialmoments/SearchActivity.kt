package com.theerthkr.essentialmoments

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.media.mediaThumbnailRequest
import com.theerthkr.essentialmoments.permissions.AppPermissions
import com.theerthkr.essentialmoments.ui.model.SearchModelGate
import com.theerthkr.essentialmoments.ui.model.openModelManager
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val SEARCH_MESSAGES = listOf(
    "Dusting off the neurons…",
    "Asking the vectors nicely…",
    "Doing math at the speed of light…",
    "Squinting at your photos…",
    "Consulting the embedding oracle…",
    "Wrangling 768 dimensions…",
    "Teaching math to look at pictures…",
    "Almost there, probably…",
    "Convincing floats to cooperate…",
    "Running cosine yoga…",
)

class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Ensures modern immersive UI
        setContent {
            EssentialMomentsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SearchScreen(onBack = { finish() })
                }
            }
        }
    }
}

// --- Top-Level Composables (Placed outside class to avoid receiver errors) ---

@Composable
fun SearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(context))
    val resultCellSizePx = rememberThumbnailSizePx(3)
    val lifecycleOwner = LocalLifecycleOwner.current

    val query by vm.query.collectAsStateWithLifecycle()
    val searchState by vm.searchState.collectAsStateWithLifecycle()
    val indexingState by vm.indexingState.collectAsStateWithLifecycle()
    var mediaAccess by remember { mutableStateOf(AppPermissions.mediaAccess(context)) }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mediaAccess = AppPermissions.mediaAccess(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mediaAccess = AppPermissions.mediaAccess(context)
                vm.refreshVisibleResults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val app = context.applicationContext as App
    // Only text-model readiness gates this screen. Collecting the whole model
    // status here would invalidate this composable (and its results grid) on
    // every download-progress tick.
    val textModelReady by remember(app) {
        app.modelManager.state
            .map { it.textReady }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = app.modelManager.state.value.textReady)

    val focusManager = LocalFocusManager.current
    // Single submission path shared by the keyboard action and the on-screen buttons.
    val submitSearch = {
        focusManager.clearFocus()
        vm.submitSearch()
    }
    val content = resolveSearchContent(query, searchState)

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top Bar (Admin UI Merged) ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = submitSearch,
                        enabled = query.isNotBlank(),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("Search your photos…", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { vm.onQueryChanged(it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitSearch() })
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChanged("") }) {
                            Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))


        }

        HorizontalDivider()

        // ── Content Area ──────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !mediaAccess.canReadPhotos -> NoPhotoAccessHint(
                    onAllow = {
                        mediaPermissionLauncher.launch(AppPermissions.mediaPermissions())
                    },
                    onOpenSettings = { AppPermissions.openAppSettings(context) }
                )
                !textModelReady -> SearchModelGate(
                    onManage = { openModelManager(context) }
                )
                content is SearchContent.Searching -> SearchingAnimation()
                content is SearchContent.EmptyQuery -> EmptyQueryHint()
                content is SearchContent.Pending -> PendingSearchHint(
                    query = content.query,
                    onSearch = submitSearch
                )
                content is SearchContent.NoResults -> NoResultsHint(query = content.query)
                content is SearchContent.Error -> SearchErrorHint(
                    query = content.query,
                    onRetry = submitSearch
                )
                content is SearchContent.Results -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = content.results,
                            key = { it.uri },
                            contentType = { "search-result" }
                        ) { result ->
                            val imageRequest = remember(context, result.uri, resultCellSizePx) {
                                mediaThumbnailRequest(context, result.uri, resultCellSizePx)
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .clickable {
                                        val intent = Intent(context, ImageViewActivity::class.java).apply {
                                            putExtra("IMAGE_URI", result.uri)
                                            putExtra("IMAGE_NAME", result.uri.substringAfterLast("/"))
                                        }
                                        context.startActivity(intent)
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Support Composables for Animation and Hints from source 15[cite: 15]
@Composable
private fun SearchingAnimation() {
    var messageIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val shuffled = SEARCH_MESSAGES.shuffled()
        var i = 0
        while (true) {
            messageIndex = i % shuffled.size
            delay(2000)
            i++
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                // Read the pulse animation in the Draw phase instead of
                // recomposing and re-measuring this subtree every frame.
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        AnimatedContent(
            targetState = messageIndex,
            transitionSpec = {
                (fadeIn(tween(400)) + slideInVertically { it / 3 })
                    .togetherWith(fadeOut(tween(300)) + slideOutVertically { -it / 3 })
            },
            label = "message"
        ) { idx ->
            Text(
                text = SEARCH_MESSAGES[idx % SEARCH_MESSAGES.size],
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun EmptyQueryHint() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🔍", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Search your photos", style = MaterialTheme.typography.titleMedium)
        Text("Try anything — \"sunset\", \"dog\", \"birthday\"", color = Color.Gray)
    }
}

@Composable
private fun NoResultsHint(query: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🙁", fontSize = 48.sp)
        Text(
            "No results for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/** Shown when text has been entered but not searched yet, e.g. after hiding the keyboard. */
@Composable
private fun PendingSearchHint(query: String, onSearch: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🔍", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Search for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Text("Not searched yet", color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onSearch) {
            Text("Search")
        }
    }
}

@Composable
private fun SearchErrorHint(query: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "⚠️", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Couldn't search for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Text("Something went wrong. Please try again.", color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun NoPhotoAccessHint(
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "📷", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Search needs photo access",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Allow access to the photos you want Essential Moments to search.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onAllow) { Text("Allow Photo Access") }
        TextButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}