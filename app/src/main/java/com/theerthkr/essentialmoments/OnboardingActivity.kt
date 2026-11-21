package com.theerthkr.essentialmoments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import com.theerthkr.essentialmoments.indexing.IndexingScheduler
import com.theerthkr.essentialmoments.permissions.AppPermissions
import com.theerthkr.essentialmoments.ui.model.ModelDownloadScreen
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme

class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        setContent {
            EssentialMomentsTheme {
                OnboardingScreen(
                    onboardingCompleted = sharedPrefs.getBoolean("onboarding_completed", false),
                    onFinished = {
                        sharedPrefs.edit { putBoolean("onboarding_completed", true) }
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onSplashOnlyFinished = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

enum class OnboardingStep {
    Splash, Welcome, Permissions, NotificationPermission, Models, Privacy
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    onboardingCompleted: Boolean,
    onFinished: () -> Unit,
    onSplashOnlyFinished: () -> Unit
) {
    var currentStep by remember { mutableStateOf(OnboardingStep.Splash) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "OnboardingTransition"
        ) { step ->
            when (step) {
                OnboardingStep.Splash -> SplashScreen(onAnimationFinished = {
                    if (onboardingCompleted) {
                        onSplashOnlyFinished()
                    } else {
                        currentStep = OnboardingStep.Welcome
                    }
                })
                OnboardingStep.Welcome -> WelcomeStep(onNext = { currentStep = OnboardingStep.Permissions })
                OnboardingStep.Permissions -> PermissionsStep(onNext = { currentStep = OnboardingStep.NotificationPermission })
                OnboardingStep.NotificationPermission -> NotificationPermissionStep(onNext = { currentStep = OnboardingStep.Models })
                OnboardingStep.Models -> ModelDownloadScreen(
                    onContinue = { currentStep = OnboardingStep.Privacy },
                    onSkip = { currentStep = OnboardingStep.Privacy }
                )
                OnboardingStep.Privacy -> PrivacyStep(onFinished = onFinished)
            }
        }
    }
}

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val videoPath = "android.resource://${context.packageName}/${R.raw.splash_animation}"
                    setVideoPath(videoPath)
                    setOnCompletionListener {
                        onAnimationFinished()
                    }
                    start()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun WelcomeStep(onNext: () -> Unit) {
    OnboardingTemplate(
        title = "Welcome, Human! 👋",
        description = "Essential Moments is your new digital memory palace. We use some fancy AI magic to help you find photos by just describing them.",
        icon = Icons.AutoMirrored.Filled.ArrowForward,
        buttonText = "Let's Go!",
        onButtonClick = onNext
    )
}

@Composable
fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var mediaAccess by remember { mutableStateOf(AppPermissions.mediaAccess(context)) }
    val permissions = remember { AppPermissions.mediaPermissions() }
    val supportsPartialAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Re-read the system state instead of trusting the result map: Android
        // 14 can grant only READ_MEDIA_VISUAL_USER_SELECTED (partial access).
        mediaAccess = AppPermissions.mediaAccess(context)
        if (mediaAccess.canReadPhotos) {
            onNext()
        }
    }

    val description = if (supportsPartialAccess) {
        "Choose which photos Essential Moments can see. You can allow your whole library, " +
            "select just a few photos, or skip this for now. Only the photos you allow are " +
            "indexed, and everything stays on your device."
    } else {
        "Essential Moments needs access to your photos to build a private, searchable " +
            "library on this device. You can skip this for now and allow access later " +
            "from Settings."
    }

    OnboardingTemplate(
        title = "The \"Can I see?\" Part 📸",
        description = description,
        icon = Icons.Default.PhotoLibrary,
        buttonText = when {
            mediaAccess.canReadPhotos -> "Continue"
            supportsPartialAccess -> "Choose Photos"
            else -> "Allow Photo Access"
        },
        onButtonClick = {
            if (mediaAccess.canReadPhotos) {
                onNext()
            } else {
                launcher.launch(permissions)
            }
        },
        secondaryButtonText = "Skip for now",
        onSecondaryClick = onNext
    )
}

@Composable
fun NotificationPermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current

    val permission = Manifest.permission.POST_NOTIFICATIONS
    val needsPermission = AppPermissions.canRequestNotificationPermission()
    var notificationsAllowed by remember {
        mutableStateOf(AppPermissions.notificationAccess(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = AppPermissions.notificationAccess(context)
        if (granted && notificationsAllowed) {
            IndexingScheduler.setBackgroundIndexingEnabled(context, true)
        } else {
            IndexingScheduler.setBackgroundIndexingEnabled(context, false)
        }
        if (granted) {
            onNext()
        }
    }

    OnboardingTemplate(
        title = "The \"Ding!\" Part 🔔",
        description = if (notificationsAllowed) {
            "Notifications let you see background indexing progress and stop it from the " +
                "notification shade. You can change this later in Settings."
        } else {
            "Background indexing needs a notification so you can see progress and stop it. " +
                "Without notifications, the app still works, but new photos are only indexed " +
                "when you ask. You can enable this later in Settings."
        },
        icon = Icons.Default.Notifications,
        buttonText = if (notificationsAllowed || !needsPermission) {
            "Continue"
        } else {
            "Allow Notifications"
        },
        onButtonClick = {
            when {
                notificationsAllowed -> {
                    IndexingScheduler.setBackgroundIndexingEnabled(context, true)
                    onNext()
                }
                needsPermission -> launcher.launch(permission)
                else -> {
                    // Android 11–12 have no runtime notification permission. If
                    // notifications are disabled system-wide, background
                    // indexing stays off until the user enables them in Settings.
                    IndexingScheduler.setBackgroundIndexingEnabled(context, false)
                    onNext()
                }
            }
        },
        secondaryButtonText = "Not now",
        onSecondaryClick = {
            IndexingScheduler.setBackgroundIndexingEnabled(context, false)
            onNext()
        }
    )
}

@Composable
fun PrivacyStep(onFinished: () -> Unit) {
    OnboardingTemplate(
        title = "Safe as a Snail in a Shell 🐌",
        description = "Worried about your privacy? Don't be! Everything happens right here on your phone. No servers, no clouds, no nosy aliens. Your data is your data. Local is the new cool.",
        icon = Icons.Default.Lock,
        buttonText = "Get Started",
        onButtonClick = onFinished
    )
}

@Composable
fun OnboardingTemplate(
    title: String,
    description: String,
    icon: ImageVector,
    buttonText: String,
    onButtonClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.height(64.dp))

        Button(
            onClick = onButtonClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(text = buttonText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        if (secondaryButtonText != null && onSecondaryClick != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onSecondaryClick) {
                Text(
                    text = secondaryButtonText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}