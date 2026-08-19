package com.jinof.apm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF355F59),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9ECE1),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4B635E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF07201B),
    tertiary = Color(0xFF6A5D3F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E1B8),
    onTertiaryContainer = Color(0xFF231B00),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF181D1B),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF181D1B),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBFC9C5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD0C6),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF1B4F49),
    onPrimaryContainer = Color(0xFFB9ECE1),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1D3530),
    secondaryContainer = Color(0xFF344B46),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFD6C594),
    onTertiary = Color(0xFF392F15),
    tertiaryContainer = Color(0xFF514624),
    onTertiaryContainer = Color(0xFFF3E1B8),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE0E4E1),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE0E4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
)

private val ApmTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val ApmShapes = Shapes(
    small = RoundedCornerShape(10),
    medium = RoundedCornerShape(18),
    large = RoundedCornerShape(28),
)

@Composable
fun ApmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ApmTypography,
        shapes = ApmShapes,
        content = content,
    )
}
