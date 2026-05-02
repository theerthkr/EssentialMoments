package com.theerthkr.essentialmoments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.work.WorkManager
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

    override fun onStop() {
        super.onStop()
        // RESUME indexing worker when the user leaves the search screen[cite: 16]
        resumeIndexing()
    }

    private fun resumeIndexing() {
        val inputData = androidx.work.workDataOf("KEY_INDEX_ALL" to true)
        val indexingRequest = androidx.work.OneTimeWorkRequestBuilder<IndexingWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "image_indexing_task",
            androidx.work.ExistingWorkPolicy.KEEP, // Keep existing work instead of resetting[cite: 16]
            indexingRequest
        )
        Log.d("SearchActivity", "Resumed Indexing Worker via onStop()[cite: 16]")
    }
}

// --- Top-Level Composables (Placed outside class to avoid receiver errors) ---

@Composable
fun SearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(context))

    val query by vm.query.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val isSearching by vm.isSearching.collectAsStateWithLifecycle()
    val indexingState by vm.indexingState.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current



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
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
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
                            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                // Pause background work before intensive search[cite: 16]
                                WorkManager.getInstance(context).cancelUniqueWork("image_indexing_task")
                                focusManager.clearFocus()
                                vm.search(query)
                            })
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
                isSearching -> SearchingAnimation()
                query.isEmpty() -> EmptyQueryHint()
                searchResults.isEmpty() -> NoResultsHint(query = query)
                else -> {
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
                .size((64 * scale).dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size((32 * scale).dp),
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
        Text("No results for \"$query\"", style = MaterialTheme.typography.titleMedium)
    }
}