// Generated: the same recipe in every app of the suite (Synchrotron, Griffonote, Ecoutify, Météorite).
package app.ecoutify.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fixed brand colors (never the wallpaper's), from the app accent #1AAE9F: Material's tonal-spot
 * scheme for surfaces and containers, the accent at full strength for `primary`.
 */
internal val SuiteLightColors = lightColorScheme(
    primary = Color(0xFF006A61),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9F0E4),
    onPrimaryContainer = Color(0xFF075C54),
    inversePrimary = Color(0xFF5ADACA),
    secondary = Color(0xFF4B6460),
    onSecondary = Color(0xFFE3FFF9),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF3D5652),
    tertiary = Color(0xFF346578),
    onTertiary = Color(0xFFF2FAFF),
    tertiaryContainer = Color(0xFFADDFF5),
    onTertiaryContainer = Color(0xFF1C5163),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF2A3432),
    surface = Color(0xFFF6FAF8),
    onSurface = Color(0xFF2A3432),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF56615F),
    surfaceTint = Color(0xFF006A61),
    inverseSurface = Color(0xFF0A0F0E),
    inverseOnSurface = Color(0xFF999E9C),
    error = Color(0xFFA83836),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFA746F),
    onErrorContainer = Color(0xFF6E0A12),
    outline = Color(0xFF727D7A),
    outlineVariant = Color(0xFFA9B4B1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FAF8),
    surfaceContainer = Color(0xFFE7F0ED),
    surfaceContainerHigh = Color(0xFFE1EAE7),
    surfaceContainerHighest = Color(0xFFDAE5E2),
    surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD1DCD9),
)

internal val SuiteDarkColors = darkColorScheme(
    primary = Color(0xFF5ADACA),
    onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF275B54),
    onPrimaryContainer = Color(0xFFB8EEE4),
    inversePrimary = Color(0xFF006A61),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF2C4541),
    secondaryContainer = Color(0xFF27403C),
    onSecondaryContainer = Color(0xFFAAC5BF),
    tertiary = Color(0xFFC2ECFF),
    onTertiary = Color(0xFF27596C),
    tertiaryContainer = Color(0xFFADDFF5),
    onTertiaryContainer = Color(0xFF1C5163),
    background = Color(0xFF0A0F0E),
    onBackground = Color(0xFFDCE8E5),
    surface = Color(0xFF0A0F0E),
    onSurface = Color(0xFFDCE8E5),
    surfaceVariant = Color(0xFF1E2826),
    onSurfaceVariant = Color(0xFFA2ADAA),
    surfaceTint = Color(0xFF5ADACA),
    inverseSurface = Color(0xFFF6FAF8),
    inverseOnSurface = Color(0xFF515655),
    error = Color(0xFFFA746F),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFF9993),
    outline = Color(0xFF6D7875),
    outlineVariant = Color(0xFF3F4A48),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF242E2C),
    surfaceContainer = Color(0xFF131B1A),
    surfaceContainerHigh = Color(0xFF192120),
    surfaceContainerHighest = Color(0xFF1E2826),
    surfaceContainerLow = Color(0xFF0E1513),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF0A0F0E),
)

/** Rounded shapes shared by the whole suite. */
internal val SuiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
