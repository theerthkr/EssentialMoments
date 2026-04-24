package com.theerthkr.essentialmoments

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme
import kotlinx.coroutines.delay

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

@Composable
fun SearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(context))

    val query by vm.query.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val indexingState by vm.indexingState.collectAsStateWithLifecycle()
    val isSearching by vm.isSearching.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) vm.indexImages(uris)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top Bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search your photos…",
                                style = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 15.sp
                                )
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { vm.onQueryChanged(it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                focusManager.clearFocus()
                                vm.search(query)
                            })
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { vm.onQueryChanged("") },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = { imagePicker.launch("image/*") },
                modifier = Modifier.size(48.dp),
                enabled = indexingState is IndexingState.Idle || indexingState is IndexingState.Done
            ) {
                Text(text = "⊕", fontSize = 22.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }

        // ── Indexing Progress Banner ──────────────────────────────
        AnimatedVisibility(
            visible = indexingState is IndexingState.Indexing || indexingState is IndexingState.Done,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val bannerText = when (val s = indexingState) {
                is IndexingState.Indexing -> "Indexing ${s.current} / ${s.total}…"
                is IndexingState.Done -> "✓ Indexed ${s.count} new photo${if (s.count != 1) "s" else ""}"
                else -> ""
            }
            val bannerColor = when (indexingState) {
                is IndexingState.Done -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bannerColor)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (indexingState is IndexingState.Indexing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        HorizontalDivider()

        // ── Content Area ──────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // Searching — show animated loading screen
                isSearching -> {
                    SearchingAnimation()
                }

                query.isEmpty() -> {
                    EmptyQueryHint()
                }

                query.isNotEmpty() && searchResults.isEmpty() -> {
                    NoResultsHint(query = query)
                }

                searchResults.isNotEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(searchResults, key = { it.uri }) { result ->
                            AsyncImage(
                                model = result.uri,
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

@Composable
private fun SearchingAnimation() {
    // Rotate through quirky messages every 2 seconds
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

    // Pulsing scale on the search icon
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
        // Pulsing icon
        Box(
            modifier = Modifier
                .size((64 * scale).dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "🔍", fontSize = (28 * scale).sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Animated message crossfade
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

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Searching your photos…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Text(
            text = "Search your photos",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Try anything — \"sunset\", \"dog\", \"birthday\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap ⊕ to index photos first",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
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
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No results for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Try different words, or index more photos",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}