package com.liuguang.downloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class DownloaderPalette(
    val shell: Color,
    val surfaceSoft: Color,
    val accent: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val textTertiary: Color,
    val divider: Color,
    val dividerStrong: Color
)

private val LightPalette = DownloaderPalette(
    shell = Color(0xFFEDF2EC),
    surfaceSoft = Color(0xFFF1F5F0),
    accent = Color(0xFFB84A4F),
    success = Color(0xFF167A5B),
    successSoft = Color(0x1F167A5B),
    warning = Color(0xFF996316),
    textTertiary = Color(0xFF62756C),
    divider = Color(0x1F284A3A),
    dividerStrong = Color(0x33284A3A)
)

private val DarkPalette = DownloaderPalette(
    shell = Color(0xFF171F1A),
    surfaceSoft = Color(0xFF222E26),
    accent = Color(0xFFFF9B8E),
    success = Color(0xFF72D8A8),
    successSoft = Color(0x2772D8A8),
    warning = Color(0xFFE7BC6A),
    textTertiary = Color(0xFF8EA5A1),
    divider = Color(0x26FFFFFF),
    dividerStrong = Color(0x4AFFFFFF)
)

private val LocalDownloaderPalette = staticCompositionLocalOf { LightPalette }

val MaterialTheme.downloaderPalette: DownloaderPalette
    @Composable get() = LocalDownloaderPalette.current

private fun downloaderColorScheme(darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF82C99C),
            onPrimary = Color(0xFF10271A),
            primaryContainer = Color(0x2D82C99C),
            onPrimaryContainer = Color(0xFFEDF5F2),
            secondary = Color(0xFFFF9B8E),
            onSecondary = Color(0xFF10271A),
            secondaryContainer = Color(0x2EFF9B8E),
            onSecondaryContainer = Color(0xFFEDF5F2),
            background = Color(0xFF111713),
            onBackground = Color(0xFFEDF5F2),
            surface = Color(0xFF1D2821),
            surfaceContainer = Color(0xFF1D2821),
            surfaceContainerHigh = Color(0xFF314036),
            surfaceContainerHighest = Color(0xFF27342B),
            surfaceVariant = Color(0xFF27342B),
            onSurface = Color(0xFFEDF5F2),
            onSurfaceVariant = Color(0xFFB7C8C4),
            outline = DarkPalette.divider,
            outlineVariant = DarkPalette.dividerStrong,
            error = Color(0xFFFF8D9E),
            onError = Color(0xFF10271A)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2F6B4F),
            onPrimary = Color.White,
            primaryContainer = Color(0x1F2F6B4F),
            onPrimaryContainer = Color(0xFF1D2B23),
            secondary = Color(0xFFB84A4F),
            onSecondary = Color.White,
            secondaryContainer = Color(0x1FB84A4F),
            onSecondaryContainer = Color(0xFF1D2B23),
            background = Color(0xFFF6F8F5),
            onBackground = Color(0xFF1D2B23),
            surface = Color.White,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color(0xFFE7EEE7),
            surfaceVariant = Color(0xFFE7EEE7),
            onSurface = Color(0xFF1D2B23),
            onSurfaceVariant = Color(0xFF50625A),
            outline = LightPalette.divider,
            outlineVariant = LightPalette.dividerStrong,
            error = Color(0xFFC33F52),
            onError = Color.White
        )
    }
}

private val LiuguangShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(6.dp)
)

// author: long - 下载器与流光共用系统字体，避免品牌一致性以牺牲动态字号和中文字形回退为代价。
private val LiuguangTypography = Typography()

@Composable
fun LiuguangDownloaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalDownloaderPalette provides palette) {
        MaterialTheme(
            colorScheme = downloaderColorScheme(darkTheme),
            shapes = LiuguangShapes,
            typography = LiuguangTypography,
            content = content
        )
    }
}
