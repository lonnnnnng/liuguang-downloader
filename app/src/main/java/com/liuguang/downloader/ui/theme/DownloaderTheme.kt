package com.liuguang.downloader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0077B6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEAFF),
    onPrimaryContainer = Color(0xFF00344F),
    secondary = Color(0xFF0E8F7E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4F6FF),
    onSecondaryContainer = Color(0xFF00344F),
    tertiary = Color(0xFF6B7CFF),
    onTertiary = Color.White,
    background = Color(0xFFF1F9FF),
    onBackground = Color(0xFF13212B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF13212B),
    surfaceVariant = Color(0xFFDDECF5),
    onSurfaceVariant = Color(0xFF5C6D79),
    outline = Color(0xFFCBDFEA),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86BDF2),
    onPrimary = Color(0xFF06213A),
    secondary = Color(0xFF7DD3C7),
    background = Color(0xFF101418),
    surface = Color(0xFF161B21),
    surfaceVariant = Color(0xFF27313A),
    onSurface = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFFAAB6C3)
)

private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

@Composable
fun LiuguangDownloaderTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        shapes = SquareShapes,
        typography = MaterialTheme.typography,
        content = content
    )
}
