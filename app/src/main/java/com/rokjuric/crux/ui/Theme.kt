package com.rokjuric.crux.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Rope orange on slate: warm accent, calm surfaces.
private val Light = lightColorScheme(
    primary = Color(0xFFB8501C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF3A1300),
    secondary = Color(0xFF3F6374),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E6F2),
    onSecondaryContainer = Color(0xFF0C1E28),
    tertiary = Color(0xFF4C6A3A),
    tertiaryContainer = Color(0xFFCDEBB4),
    onTertiaryContainer = Color(0xFF0D2000),
    background = Color(0xFFFBF8F5),
    surface = Color(0xFFFBF8F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F1EC),
    surfaceContainer = Color(0xFFF0EAE4),
    surfaceContainerHigh = Color(0xFFEAE4DE),
    surfaceContainerHighest = Color(0xFFE4DED8),
    outlineVariant = Color(0xFFD8CFC8),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB592),
    onPrimary = Color(0xFF5B1F00),
    primaryContainer = Color(0xFF8A3A0E),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFA7CBDE),
    onSecondary = Color(0xFF0C3443),
    secondaryContainer = Color(0xFF274B5B),
    onSecondaryContainer = Color(0xFFD2E6F2),
    tertiary = Color(0xFFB1CF99),
    tertiaryContainer = Color(0xFF354F24),
    onTertiaryContainer = Color(0xFFCDEBB4),
    background = Color(0xFF14181C),
    surface = Color(0xFF14181C),
    surfaceContainerLowest = Color(0xFF0F1215),
    surfaceContainerLow = Color(0xFF1B2025),
    surfaceContainer = Color(0xFF1F252A),
    surfaceContainerHigh = Color(0xFF293035),
    surfaceContainerHighest = Color(0xFF343B40),
    outlineVariant = Color(0xFF41484D),
)

@Composable
fun CruxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
