package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = ColdNavyPrimary,
    onPrimary = Color.White,
    primaryContainer = IceCyanSoft,
    onPrimaryContainer = ColdNavyPrimary,
    secondary = IceCyanAccent,
    onSecondary = Color.White,
    secondaryContainer = FrostSurfaceVariant,
    onSecondaryContainer = ColdNavyPrimary,
    tertiary = ButcherCrimson,
    onTertiary = Color.White,
    tertiaryContainer = ButcherCrimsonSoft,
    onTertiaryContainer = ButcherCrimson,
    background = FrostBackground,
    onBackground = SlateTextPrimary,
    surface = FrostSurface,
    onSurface = SlateTextPrimary,
    surfaceVariant = FrostSurfaceVariant,
    onSurfaceVariant = SlateTextSecondary,
    outline = FrostOutline,
    error = StatusDangerRed,
    onError = Color.White,
    errorContainer = StatusDangerRedBg,
    onErrorContainer = StatusDangerRed
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = ColdNavyPrimary,
    primaryContainer = ColdNavyLight,
    onPrimaryContainer = IceCyanSoft,
    secondary = Color(0xFF38BDF8),
    onSecondary = DeepColdBackground,
    secondaryContainer = DeepColdSurfaceVariant,
    onSecondaryContainer = IceTextPrimary,
    tertiary = Color(0xFFFCA5A5),
    onTertiary = Color(0xFF450A0A),
    tertiaryContainer = Color(0xFF5C151B),
    onTertiaryContainer = Color(0xFFFEE2E2),
    background = DeepColdBackground,
    onBackground = IceTextPrimary,
    surface = DeepColdSurface,
    onSurface = IceTextPrimary,
    surfaceVariant = DeepColdSurfaceVariant,
    onSurfaceVariant = IceTextSecondary,
    outline = DeepColdOutline,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = StatusDangerRedDarkBg,
    onErrorContainer = Color(0xFFFECACA)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun FrigStockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
