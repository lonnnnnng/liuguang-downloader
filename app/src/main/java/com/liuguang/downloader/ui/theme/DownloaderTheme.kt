package com.liuguang.downloader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// long 2026-09-05: 同步流光主项目的 tita 浅色风格：白底扁平、绿色主色、红色强调，应用只保留浅色主题。
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

// 色值取自流光 LiuguangLightPalette；surfaceSoft 承担主项目 SurfaceAlt 的角色（白底上的中性填充）。
private val LightPalette = DownloaderPalette(
    shell = Color(0xFFF7F7F7),
    surfaceSoft = Color(0xFFF3F3F3),
    accent = Color(0xFFD32F2F),
    success = Color(0xFF1E9C5A),
    successSoft = Color(0xFFDDF2E9),
    warning = Color(0xFFF08332),
    textTertiary = Color(0xFF999999),
    divider = Color(0xFFF0F0F0),
    dividerStrong = Color(0xFFE5E5E5)
)

private val LocalDownloaderPalette = staticCompositionLocalOf { LightPalette }

val MaterialTheme.downloaderPalette: DownloaderPalette
    @Composable get() = LocalDownloaderPalette.current

// 与流光 LiuguangTheme 的 lightColorScheme 映射保持一致。
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1E9C5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF2E9),
    onPrimaryContainer = Color(0xFF202020),
    secondary = Color(0xFFD32F2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0x14D32F2F),
    onSecondaryContainer = Color(0xFF202020),
    background = Color.White,
    onBackground = Color(0xFF202020),
    surface = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF3F3F3),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurface = Color(0xFF202020),
    onSurfaceVariant = Color(0xFF606060),
    outline = LightPalette.divider,
    outlineVariant = LightPalette.dividerStrong,
    error = Color(0xFFD32F2F),
    onError = Color.White,
    inverseSurface = Color(0xFF202020),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFF1E9C5A)
)

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
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDownloaderPalette provides LightPalette) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            shapes = LiuguangShapes,
            typography = LiuguangTypography,
            content = content
        )
    }
}
