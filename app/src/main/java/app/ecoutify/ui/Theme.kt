package app.ecoutify.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA99BFF),
    onPrimary = Color(0xFF1A1442),
    primaryContainer = Color(0xFF32296B),
    onPrimaryContainer = Color(0xFFE9E5FF),
    secondary = Color(0xFF4DE1C1),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF2E2766),
    onSecondaryContainer = Color(0xFFEDE9FF),
    background = Color(0xFF0B0B10),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF0B0B10),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF24242F),
    onSurfaceVariant = Color(0xFFA7A7B8),
    surfaceContainer = Color(0xFF15151C),
    surfaceContainerHigh = Color(0xFF1D1D26),
    outline = Color(0xFF6E6E80),
    outlineVariant = Color(0xFF2E2E3A),
    error = Color(0xFFFF7A7A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B47E0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF1B1259),
    secondary = Color(0xFF00796B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCD6FF),
    onSecondaryContainer = Color(0xFF1B1259),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF16141F),
    surface = Color(0xFFF8F7FC),
    onSurface = Color(0xFF16141F),
    surfaceVariant = Color(0xFFE3E0EE),
    onSurfaceVariant = Color(0xFF5C5A6B),
    surfaceContainer = Color(0xFFEFEDF7),
    surfaceContainerHigh = Color(0xFFE6E3F2),
    outline = Color(0xFF7A7889),
    outlineVariant = Color(0xFFD2CFE0),
    error = Color(0xFFB3261E),
)

/** Follows the system light/dark setting. The root Surface gives text a readable default color. */
@Composable
fun EcoutifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
