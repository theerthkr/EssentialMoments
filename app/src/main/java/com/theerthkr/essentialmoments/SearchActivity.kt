package com.theerthkr.essentialmoments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme

class SearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EssentialMomentsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SearchScreen()
                }
            }
        }
    }
}

@Composable
fun SearchScreen(searchViewModel: SearchViewModel = viewModel()) {
    val context        = LocalContext.current as Activity
    val focusRequester = remember { FocusRequester() }

    // Collect state
    val query          by searchViewModel.query.collectAsStateWithLifecycle()
    val results        by searchViewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching    by searchViewModel.isSearching.collectAsStateWithLifecycle()
    val indexingState  by searchViewModel.indexingState.collectAsStateWithLifecycle()

    // Auto-focus keyboard when screen opens
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {

        // ── Top bar: back button + search field ────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { context.finish() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            BasicTextField(
                value          = query,
                onValueChange  = { searchViewModel.onQueryChanged(it) },
                modifier       = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .height(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                textStyle = TextStyle(
                    color    = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine  = true,
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            "Search your photos…",
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                    innerTextField()
                }
            )

            // Clear button — only visible when there's text
            AnimatedVisibility(visible = query.isNotEmpty()) {
                IconButton(onClick = { searchViewModel.onQueryChanged("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Indexing status banner ─────────────────────────────────
        IndexingBanner(
            state          = indexingState,
            onStartIndexing = { searchViewModel.startIndexing() }
        )

        // ── Body ───────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // Searching spinner
                isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Searching…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Results grid
                results.isNotEmpty() -> {
                    Column {
                        Text(
                            "${results.size} results for \"$query\"",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SearchResultsGrid(
                            images  = results,
                            onClick = { image ->
                                val intent = Intent(context, ImageViewActivity::class.java).apply {
                                    putExtra("IMAGE_URI",  image.uri)
                                    putExtra("IMAGE_NAME", image.id.toString())
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                // No results (query typed but nothing found)
                query.isNotEmpty() && !isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No results for \"$query\"",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (indexingState is IndexingState.Idle || indexingState is IndexingState.Queued)
                                    "Try indexing your photos first"
                                else
                                    "Try a different search term",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Empty state — nothing typed yet
                else -> {
                    EmptySearchState(indexingState)
                }
            }
        }
    }
}

// ── Indexing banner ────────────────────────────────────────────────

@Composable
private fun IndexingBanner(
    state: IndexingState,
    onStartIndexing: () -> Unit
) {
    AnimatedVisibility(
        visible = state !is IndexingState.Done,
        enter   = expandVertically(),
        exit    = shrinkVertically()
    ) {
        when (state) {
            is IndexingState.Idle -> {
                // Prompt user to start indexing
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Photos not indexed yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onStartIndexing) {
                        Text("Index now")
                    }
                }
            }

            is IndexingState.Queued -> {
                LinearProgressBanner(
                    label    = "Indexing queued…",
                    progress = null   // indeterminate
                )
            }

            is IndexingState.Running -> {
                val progress = if (state.total > 0)
                    state.indexed.toFloat() / state.total.toFloat()
                else null

                LinearProgressBanner(
                    label    = "Indexing: ${state.indexed} / ${state.total}" +
                            if (state.failed > 0) "  (${state.failed} failed)" else "",
                    progress = progress
                )
            }

            is IndexingState.Failed -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Indexing failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onStartIndexing) {
                        Text("Retry")
                    }
                }
            }

            is IndexingState.Done -> { /* banner hidden by AnimatedVisibility */ }
        }
    }
}

@Composable
private fun LinearProgressBanner(label: String, progress: Float?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.height(4.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress    = { progress },
                modifier    = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// ── Results grid ───────────────────────────────────────────────────

@Composable
private fun SearchResultsGrid(
    images: List<MediaImage>,
    onClick: (MediaImage) -> Unit
) {
    LazyVerticalGrid(
        columns        = GridCells.Fixed(3),
        contentPadding = PaddingValues(2.dp)
    ) {
        items(images, key = { it.id }) { image ->
            AsyncImage(
                model              = image.uri,
                contentDescription = null,
                modifier           = Modifier
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onClick(image) },
                contentScale = ContentScale.Crop
            )
        }
    }
}

// ── Empty / idle state ─────────────────────────────────────────────

@Composable
private fun EmptySearchState(indexingState: IndexingState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment   = Alignment.CenterHorizontally,
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.padding(32.dp)
        ) {
            when (indexingState) {
                is IndexingState.Done -> {
                    Text(
                        "Search your ${indexingState.total} indexed photos",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Try: \"sunset\", \"dog\", \"birthday cake\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is IndexingState.Running -> {
                    Text(
                        "Indexing in progress…",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "You can search the photos indexed so far",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        "Your photos aren't indexed yet",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Tap \"Index now\" above to enable semantic search",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}