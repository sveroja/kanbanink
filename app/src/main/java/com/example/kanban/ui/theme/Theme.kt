package com.example.kanban.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * E-Ink-optimiertes Farbschema: reines Schwarz/Weiß, maximaler Kontrast,
 * kein Dark Mode und keine Dynamic Colors.
 */
private val EInkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    tertiary = Color.Black,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Color.Black,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EInkColorScheme,
        typography = Typography,
    ) {
        // Ripple-Animationen abschalten – auf E-Ink verursachen sie Ghosting.
        CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
    }
}
