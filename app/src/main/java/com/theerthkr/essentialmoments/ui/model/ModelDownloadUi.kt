package com.theerthkr.essentialmoments.ui.model

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theerthkr.essentialmoments.App
import com.theerthkr.essentialmoments.ModelDownloadActivity
import com.theerthkr.essentialmoments.ml.model.ModelRole
import com.theerthkr.essentialmoments.ui.theme.LogoRed
import kotlinx.coroutines.launch

private const val FALLBACK_VISION_MB = 91L
private const val FALLBACK_TEXT_MB = 272L

/**
 * Full "what / why / how big" model setup screen.
 *
 * Used both as an onboarding step and as a standalone manager reachable from
 * Settings and the gallery banner.
 */
@Composable
fun ModelDownloadScreen(
    onBack: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val status by app.modelManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(app.modelManager.isWifiOnly()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Refresh the manifest so updates are discoverable; failures are silent
        // when models are already installed (offline use is supported).
        app.modelManager.fetchManifest()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (onBack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "AI Models",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = if (onBack == null) 32.dp else 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (status.fullyReady) Icons.Default.CheckCircle
                        else Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = when {
                        status.fullyReady -> "AI is ready"
                        status.visionReady -> "Almost there"
                        else -> "Download the AI, once"
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (status.fullyReady) {
                        "Both models are installed. Everything runs on your phone, " +
                            "even without a connection."
                    } else {
                        "Two on-device neural networks power Essential Moments. " +
                            "They are downloaded once, run entirely on your phone, and your " +
                            "photos never leave the device."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ModelRow(
                            icon = Icons.Default.ImageSearch,
                            title = "Photo understanding",
                            subtitle = "Finds photos that match a description",
                            sizeMb = FALLBACK_VISION_MB,
                            ready = status.visionReady
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                        ModelRow(
                            icon = Icons.Default.Search,
                            title = "Search understanding",
                            subtitle = "Turns your words into a visual search",
                            sizeMb = FALLBACK_TEXT_MB,
                            ready = status.textReady
                        )

                        val totalMb = (status.totalBytes / (1024 * 1024)).takeIf { it > 0 }
                            ?: (FALLBACK_VISION_MB + FALLBACK_TEXT_MB)
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "About $totalMb MB total · needs ~${(totalMb * 1.1).toLong()} MB free",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Download over Wi-Fi only",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Recommended for the large search model. " +
                                "Cellular downloads are allowed by default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            app.modelManager.setWifiOnly(it)
                        }
                    )
                }

                status.download?.let { progress ->
                    Spacer(modifier = Modifier.height(20.dp))
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Downloading ${progress.role.displayName.lowercase()}",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = LogoRed,
                                trackColor = MaterialTheme.colorScheme.surface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${formatMb(progress.overallBytes)} of " +
                                    "${formatMb(progress.overallTotalBytes)} · " +
                                    "${formatMb(progress.speedBytesPerSecond)}/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (status.queued && status.download == null && status.error == null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (app.modelManager.isWifiOnly()) {
                            "Waiting for a Wi-Fi connection…"
                        } else {
                            "Waiting for a network connection…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                status.error?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (status.updateAvailable) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "A newer model version (v${status.availableVersion}) is available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                when {
                    status.fullyReady -> {
                        if (status.updateAvailable) {
                            OutlinedButton(
                                onClick = { app.modelManager.startDownload() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Download update (v${status.availableVersion})")
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        Button(
                            onClick = { onContinue?.invoke() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = if (onContinue != null) "Continue" else "Done",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    status.download != null || status.queued -> {
                        OutlinedButton(
                            onClick = { app.modelManager.pauseDownload() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                Icons.Default.PauseCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pause download")
                        }
                        if (onContinue != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onContinue,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Continue in background")
                            }
                        }
                    }

                    else -> {
                        val enabled = !status.checking
                        Button(
                            onClick = {
                                scope.launch { app.modelManager.startDownload() }
                            },
                            enabled = enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (status.checking) {
                                    "Checking for models…"
                                } else {
                                    "Download all (~${FALLBACK_VISION_MB + FALLBACK_TEXT_MB} MB)"
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (onSkip != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onSkip,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Set up later")
                            }
                        }
                    }
                }

                if (status.readyRoles.contains(ModelRole.VISION) &&
                    status.download == null &&
                    !status.fullyReady
                ) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Your photo index can already start building. " +
                            "Search becomes available when the second download finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (status.visionReady && status.download == null && !status.queued) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text(
                            text = "Delete downloaded models",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete AI models?") },
            text = {
                Text(
                    "This frees about 363 MB. Photo indexing stops and search will be " +
                        "unavailable until you download the models again. Your existing " +
                        "photo index is kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        app.modelManager.deleteModels()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ModelRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    sizeMb: Long,
    ready: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (ready) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Installed",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = "$sizeMb MB",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Compact gallery banner shown while the vision model is missing. */
@Composable
fun ModelSetupBanner(onClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val status by app.modelManager.state.collectAsStateWithLifecycle()
    if (status.visionReady) return

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Set up AI search",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Download the on-device models to index and search your photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Inline gate shown in Search when the text model has not been installed. */
@Composable
fun SearchModelGate(onManage: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val status by app.modelManager.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (!status.visionReady) "Set up AI search" else "Download the search model",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (!status.visionReady) {
                "Download the on-device models to search your photos by description. " +
                    "About 363 MB, downloaded once."
            } else {
                "One last download (about 272 MB) lets you search using plain words. " +
                    "Your photos stay on this device."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        status.download?.let { progress ->
            Spacer(modifier = Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = LogoRed,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${formatMb(progress.overallBytes)} of " +
                    "${formatMb(progress.overallTotalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                if (status.download == null && !status.queued) {
                    app.modelManager.startDownload()
                }
                onManage()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = when {
                    status.download != null || status.queued -> "View download"
                    status.visionReady -> "Download search model"
                    else -> "Download models"
                },
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Helper used by screens that want to open the model manager. */
fun openModelManager(context: android.content.Context) {
    context.startActivity(Intent(context, ModelDownloadActivity::class.java))
}

private fun formatMb(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100) "%.0f MB".format(mb) else "%.1f MB".format(mb)
}
