package com.liuguang.downloader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1598D4),
    onPrimary = Color.White,
    primaryContainer = Color(0x211598D4),
    onPrimaryContainer = Color(0xFF102D3D),
    secondary = Color(0xFF20B8C8),
    onSecondary = Color.White,
    secondaryContainer = Color(0x1F20B8C8),
    onSecondaryContainer = Color(0xFF102D3D),
    tertiary = Color(0xFF16885E),
    onTertiary = Color.White,
    background = Color(0xFFF5FAFD),
    onBackground = Color(0xFF102D3D),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE6F2F8),
    onSurface = Color(0xFF102D3D),
    surfaceVariant = Color(0xFFE6F2F8),
    onSurfaceVariant = Color(0xFF526D7C),
    outline = Color(0x1F0D415B),
    outlineVariant = Color(0x330D415B),
    error = Color(0xFFD23B55),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD7DEE8),
    onPrimary = Color(0xFF0A0B0D),
    primaryContainer = Color(0x2ED7DEE8),
    onPrimaryContainer = Color(0xFFF3F5F7),
    secondary = Color(0xFFAEB8C5),
    onSecondary = Color(0xFF0A0B0D),
    secondaryContainer = Color(0x24AEB8C5),
    onSecondaryContainer = Color(0xFFF3F5F7),
    background = Color(0xFF050506),
    onBackground = Color(0xFFF3F5F7),
    surface = Color(0xFF111214),
    surfaceContainer = Color(0xFF111214),
    surfaceContainerHigh = Color(0xFF22252B),
    surfaceContainerHighest = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFF1A1C20),
    onSurface = Color(0xFFF3F5F7),
    onSurfaceVariant = Color(0xFFB3B8C0),
    outline = Color(0x26FFFFFF),
    outlineVariant = Color(0x3BFFFFFF),
    error = Color(0xFFFF7676),
    onError = Color(0xFF0A0B0D)
)

private val LiuguangShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(6.dp)
)

private val HeitiFontFamily = FontFamily.SansSerif

private val LiuguangTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.withHeiti(),
        displayMedium = displayMedium.withHeiti(),
        displaySmall = displaySmall.withHeiti(),
        headlineLarge = headlineLarge.withHeiti(),
        headlineMedium = headlineMedium.withHeiti(),
        headlineSmall = headlineSmall.withHeiti(),
        titleLarge = titleLarge.withHeiti(),
        titleMedium = titleMedium.withHeiti(),
        titleSmall = titleSmall.withHeiti(),
        bodyLarge = bodyLarge.withHeiti(),
        bodyMedium = bodyMedium.withHeiti(),
        bodySmall = bodySmall.withHeiti(),
        labelLarge = labelLarge.withHeiti(),
        labelMedium = labelMedium.withHeiti(),
        labelSmall = labelSmall.withHeiti()
    )
}

private fun TextStyle.withHeiti(): TextStyle = copy(fontFamily = HeitiFontFamily)

@Composable
fun LiuguangDownloaderTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        shapes = LiuguangShapes,
        typography = LiuguangTypography,
        content = content
    )
}
