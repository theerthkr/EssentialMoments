package com.theerthkr.essentialmoments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
    Splash, Welcome, Permissions, Privacy
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
                OnboardingStep.Permissions -> PermissionsStep(onNext = { currentStep = OnboardingStep.Privacy })
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
        description = "EssentialMoments is your new digital memory palace. We use some fancy AI magic to help you find photos by just describing them.",
        icon = Icons.AutoMirrored.Filled.ArrowForward,
        buttonText = "Let's Go!",
        onButtonClick = onNext
    )
}

@Composable
fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    var isGranted by remember {
        mutableStateOf(permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        isGranted = result.values.all { it }
        if (isGranted) {
            onNext()
        }
    }

    OnboardingTemplate(
        title = "The \"Can I see?\" Part 📸",
        description = "We need permission to look at your photos. Why? Because without them, I'm just an empty app with nothing to show you. It's like a library without books! If you say no, I'll be very sad and probably won't work at all.",
        icon = Icons.Default.PhotoLibrary,
        buttonText = if (isGranted) "Already Got 'Em!" else "Sure, Take a Peek",
        onButtonClick = {
            if (isGranted) {
                onNext()
            } else {
                launcher.launch(permissions)
            }
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
    onButtonClick: () -> Unit
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
    }
}