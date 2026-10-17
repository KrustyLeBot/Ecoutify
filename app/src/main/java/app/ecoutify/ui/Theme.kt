package app.ecoutify.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Mint = Color(0xFF4DE1C1)
val Violet = Color(0xFF8B7BFF)

private val Colors = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF14102E),
    primaryContainer = Color(0xFF2B2554),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Mint,
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF0E0E12),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF1C1C24),
    onSurfaceVariant = Color(0xFF9D9DAD),
    surfaceContainer = Color(0xFF17171D),
    surfaceContainerHigh = Color(0xFF1F1F27),
    outlineVariant = Color(0xFF2A2A34),
    error = Color(0xFFFF6B6B),
)

@Composable
fun EcoutifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
