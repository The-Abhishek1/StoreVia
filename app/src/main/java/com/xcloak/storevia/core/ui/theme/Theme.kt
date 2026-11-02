package com.xcloak.storevia.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette: deep harbour blue for trust, a saffron accent for money/action. High contrast for shop lighting.
private val Blue = Color(0xFF123B5C)
private val Saffron = Color(0xFFE8A317)
private val Ink = Color(0xFF14202B)
private val Paper = Color(0xFFF6F8FA)

private val Light = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    secondary = Saffron, onSecondary = Ink,
    background = Paper, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FC1E8), onPrimary = Ink,
    secondary = Saffron, onSecondary = Ink,
    background = Color(0xFF0E1620), onBackground = Color(0xFFE6ECF2),
    surface = Color(0xFF16212D), onSurface = Color(0xFFE6ECF2),
)

@Composable
fun StoreviaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
