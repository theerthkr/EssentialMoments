package com.theerthkr.essentialmoments.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = LogoRed,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkSurface,
    surface = DarkSurface,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = DarkGrey,
    onSurfaceVariant = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = LogoRed,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color.White,
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color.Black
)

@Composable
fun EssentialMomentsTheme(
    darkTheme: Boolean = true, // Force dark theme for brand consistency
    dynamicColor: Boolean = false, // Disable dynamic color to maintain AMOLED black
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}