package com.xcloak.storevia.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import com.xcloak.storevia.R

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Blue = Color(0xFF155E75)
private val Teal = Color(0xFF0F766E)
private val Amber = Color(0xFFF2A900)
private val Ink = Color(0xFF12212B)
private val Paper = Color(0xFFF4F7F9)
private val StoreviaFont = FontFamily(Font(R.font.storevia_sans, FontWeight.Normal))

private val Light = lightColorScheme(
    primary = Blue, onPrimary = Color.White, primaryContainer = Color(0xFFD8EDF2), onPrimaryContainer = Ink,
    secondary = Teal, onSecondary = Color.White, secondaryContainer = Color(0xFFD4EEE8), onSecondaryContainer = Ink,
    tertiary = Amber, onTertiary = Ink, tertiaryContainer = Color(0xFFFFEDC2), onTertiaryContainer = Ink,
    background = Paper, onBackground = Ink,
    surface = Color.White, onSurface = Ink, surfaceVariant = Color(0xFFE7EEF2), onSurfaceVariant = Color(0xFF50616C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFB),
    surfaceContainer = Color(0xFFF0F4F6), surfaceContainerHigh = Color(0xFFE9EFF2),
    surfaceContainerHighest = Color(0xFFE1E9ED),
    outline = Color(0xFFBAC8CF), outlineVariant = Color(0xFFD8E1E5),
    error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF76C8D7), onPrimary = Color(0xFF062C36), primaryContainer = Color(0xFF174653), onPrimaryContainer = Color(0xFFD1F3F7),
    secondary = Color(0xFF77D0B7), onSecondary = Color(0xFF07382F), secondaryContainer = Color(0xFF164C40), onSecondaryContainer = Color(0xFFCBF3E8),
    tertiary = Color(0xFFFFC857), onTertiary = Color(0xFF3B2A00), tertiaryContainer = Color(0xFF55400B), onTertiaryContainer = Color(0xFFFFE7B0),
    background = Color(0xFF0B151D), onBackground = Color(0xFFE4EDF1),
    surface = Color(0xFF111F29), onSurface = Color(0xFFE4EDF1),
    surfaceVariant = Color(0xFF1C2C37), onSurfaceVariant = Color(0xFFB8C7CE),
    surfaceContainerLowest = Color(0xFF0B151D), surfaceContainerLow = Color(0xFF111F29),
    surfaceContainer = Color(0xFF172631), surfaceContainerHigh = Color(0xFF1C2C37),
    surfaceContainerHighest = Color(0xFF253640),
    outline = Color(0xFF6D818B), outlineVariant = Color(0xFF354751),
    error = Color(0xFFFFB4AB),
)

@Composable
fun StoreviaTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = MaterialTheme.typography
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = StoreviaFont),
        displayMedium = base.displayMedium.copy(fontFamily = StoreviaFont),
        displaySmall = base.displaySmall.copy(fontFamily = StoreviaFont),
        headlineLarge = base.headlineLarge.copy(fontFamily = StoreviaFont),
        headlineMedium = base.headlineMedium.copy(fontFamily = StoreviaFont),
        headlineSmall = base.headlineSmall.copy(fontFamily = StoreviaFont),
        titleLarge = base.titleLarge.copy(fontFamily = StoreviaFont),
        titleMedium = base.titleMedium.copy(fontFamily = StoreviaFont),
        titleSmall = base.titleSmall.copy(fontFamily = StoreviaFont),
        bodyLarge = base.bodyLarge.copy(fontFamily = StoreviaFont),
        bodyMedium = base.bodyMedium.copy(fontFamily = StoreviaFont),
        bodySmall = base.bodySmall.copy(fontFamily = StoreviaFont),
        labelLarge = base.labelLarge.copy(fontFamily = StoreviaFont),
        labelMedium = base.labelMedium.copy(fontFamily = StoreviaFont),
        labelSmall = base.labelSmall.copy(fontFamily = StoreviaFont),
    )
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typography, content = content)
}
