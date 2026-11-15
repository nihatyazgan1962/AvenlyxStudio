package com.formatci.mediastudio.core.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.formatci.mediastudio.domain.model.ThemePreference

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5B93FF),
    onPrimary = Color(0xFF001C4D),
    primaryContainer = Color(0xFF19356B),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF0B4F47),
    onSecondaryContainer = Color(0xFFA7F3E8),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5E4100),
    onTertiaryContainer = Color(0xFFFFE08C),
    background = Color(0xFF0B101B),
    onBackground = Color(0xFFE7EDF8),
    surface = Color(0xFF121826),
    onSurface = Color(0xFFE7EDF8),
    surfaceVariant = Color(0xFF1B2436),
    onSurfaceVariant = Color(0xFF9EADC8),
    surfaceContainerLowest = Color(0xFF080C15),
    surfaceContainerLow = Color(0xFF111725),
    surfaceContainer = Color(0xFF161F31),
    surfaceContainerHigh = Color(0xFF1E2940),
    surfaceContainerHighest = Color(0xFF273450),
    outline = Color(0xFF394968),
    outlineVariant = Color(0xFF253149),
    error = Color(0xFFFF6B78),
    onError = Color(0xFF4B0010),
    errorContainer = Color(0xFF6B1422),
    onErrorContainer = Color(0xFFFFD9DD)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1E5EE9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A2463),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF064E46),
    tertiary = Color(0xFFD97706),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF5C2D00),
    background = Color(0xFFF3F6FC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE5ECF6),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFE),
    surfaceContainer = Color(0xFFEEF2F9),
    surfaceContainerHigh = Color(0xFFE4EAF4),
    surfaceContainerHighest = Color(0xFFDADEEB),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

@Immutable
data class StudioExtendedColors(
    val videoAccent: Color,
    val audioAccent: Color,
    val imageAccent: Color,
    val batchAccent: Color,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val waveformActive: Color,
    val waveformInactive: Color,
    val canvasBackground: Color
)

private val DarkExtendedColors = StudioExtendedColors(
    videoAccent = Color(0xFF5B93FF),
    audioAccent = Color(0xFF2DD4BF),
    imageAccent = Color(0xFFFBBF24),
    batchAccent = Color(0xFFA78BFA),
    success = Color(0xFF34D399),
    onSuccess = Color(0xFF022C22),
    warning = Color(0xFFF59E0B),
    onWarning = Color(0xFF331B00),
    waveformActive = Color(0xFF2DD4BF),
    waveformInactive = Color(0xFF334155),
    canvasBackground = Color(0xFF070B12)
)

private val LightExtendedColors = StudioExtendedColors(
    videoAccent = Color(0xFF1E5EE9),
    audioAccent = Color(0xFF0D9488),
    imageAccent = Color(0xFFD97706),
    batchAccent = Color(0xFF7C3AED),
    success = Color(0xFF059669),
    onSuccess = Color(0xFFFFFFFF),
    warning = Color(0xFFD97706),
    onWarning = Color(0xFFFFFFFF),
    waveformActive = Color(0xFF0D9488),
    waveformInactive = Color(0xFFCBD5E1),
    canvasBackground = Color(0xFF0F172A)
)

val LocalStudioColors = staticCompositionLocalOf { DarkExtendedColors }

private val StudioTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium
    )
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun FormatciTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themePreference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme
    val extendedColors = if (useDark) DarkExtendedColors else LightExtendedColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDark
            insetsController.isAppearanceLightNavigationBars = !useDark
        }
    }

    CompositionLocalProvider(LocalStudioColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StudioTypography,
            shapes = StudioShapes,
            content = content
        )
    }
}
