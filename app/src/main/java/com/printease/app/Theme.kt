package com.printease.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF174F3E)
val Lime = Color(0xFFDEF3AA)
val Cream = Color(0xFFF7F9F5)
val Ink = Color(0xFF19352E)
val Muted = Color(0xFF64766D)
private val Light =
    lightColorScheme(
        primary = Forest,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE6F0E5),
        onPrimaryContainer = Ink,
        secondaryContainer = Lime,
        onSecondaryContainer = Ink,
        background = Cream,
        surface = Color.White,
        onSurface = Ink,
        onSurfaceVariant = Muted,
        surfaceVariant = Color(0xFFEDF1EA),
        outline = Color(0xFF76877B),
        outlineVariant = Color(0xFFDCE5D9),
    )
private val Dark =
    darkColorScheme(
        primary = Color(0xFFA8D5B5),
        onPrimary = Ink,
        primaryContainer = Forest,
        onPrimaryContainer = Color(0xFFE4F3E6),
        secondaryContainer = Color(0xFF4C6335),
        onSecondaryContainer = Lime,
        background = Color(0xFF111C17),
        surface = Color(0xFF1A2821),
        onSurface = Color(0xFFE1EDE2),
        onSurfaceVariant = Color(0xFFB7C8BA),
        outlineVariant = Color(0xFF35463A),
    )

@Composable
fun PrintEaseTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography =
            Typography(
                headlineLarge =
                    TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        lineHeight = 38.sp,
                        letterSpacing = (-1).sp,
                    ),
                headlineMedium =
                    TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        letterSpacing = (-0.5).sp,
                    ),
                titleLarge =
                    TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            ),
        content = content,
    )
}
