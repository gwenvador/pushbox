package io.github.gwenvador.pushbox

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed Pushbox palette: indigo accents on neutral, slightly cool greys.
// Every role is set so nothing falls back to Material's default purple tones,
// and wallpaper colours (Material You) are deliberately not used.

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF14105E),
    inversePrimary = Color(0xFFC0C1FF),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E3F3),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF00658A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F1FF),
    onTertiaryContainer = Color(0xFF00344A),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE1E2EA),
    onSurfaceVariant = Color(0xFF45474F),
    surfaceTint = Color(0xFF4F46E5),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F6),
    outline = Color(0xFF767882),
    outlineVariant = Color(0xFFC6C7D0),
    surfaceBright = Color(0xFFF8F9FC),
    surfaceDim = Color(0xFFD8D9E0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEDF2),
    surfaceContainerHigh = Color(0xFFE6E7EC),
    surfaceContainerHighest = Color(0xFFE0E1E7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC0C1FF),
    onPrimary = Color(0xFF1E1A8C),
    primaryContainer = Color(0xFF3B34B8),
    onPrimaryContainer = Color(0xFFE1E0FF),
    inversePrimary = Color(0xFF4F46E5),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF30333F),
    onSecondaryContainer = Color(0xFFDFE1F5),
    tertiary = Color(0xFF7FD0FF),
    onTertiary = Color(0xFF003549),
    tertiaryContainer = Color(0xFF0E3D52),
    onTertiaryContainer = Color(0xFFC4EBFF),
    background = Color(0xFF111317),
    onBackground = Color(0xFFE2E2E8),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFE2E2E8),
    surfaceVariant = Color(0xFF2C2F36),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceTint = Color(0xFFC0C1FF),
    inverseSurface = Color(0xFFE2E2E8),
    inverseOnSurface = Color(0xFF2E3036),
    outline = Color(0xFF8E909A),
    outlineVariant = Color(0xFF44464F),
    surfaceBright = Color(0xFF37393F),
    surfaceDim = Color(0xFF111317),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF191B20),
    surfaceContainer = Color(0xFF1D1F24),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

@Composable
fun PushboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
