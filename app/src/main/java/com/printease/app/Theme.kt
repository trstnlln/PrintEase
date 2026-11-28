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
val Cream = Color(0xFFF7F7F5)
val Ink = Color(0xFF19352E)
val Muted = Color(0xFF535A56)
private val Light =
    lightColorScheme(
        primary = Forest,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEFEFEC),
        onPrimaryContainer = Ink,
        secondaryContainer = Lime,
        onSecondaryContainer = Ink,
        background = Cream,
        surface = Color.White,
        onSurface = Ink,
        onSurfaceVariant = Muted,
        surfaceVariant = Color(0xFFEFEFEC),
        outline = Color(0xFF858C88),
        outlineVariant = Color(0xFFD8DAD8),
    )
private val Dark =
    darkColorScheme(
        primary = Color(0xFFA8D5B5),
        onPrimary = Ink,
        primaryContainer = Color(0xFF2D3032),
        onPrimaryContainer = Color(0xFFF1F2F3),
        secondaryContainer = Color(0xFF4C6335),
        onSecondaryContainer = Lime,
        background = Color(0xFF17181A),
        surface = Color(0xFF222426),
        onSurface = Color(0xFFF1F2F3),
        onSurfaceVariant = Color(0xFFB7BBBF),
        surfaceVariant = Color(0xFF2D3032),
        outline = Color(0xFF777E82),
        outlineVariant = Color(0xFF53585C),
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
                bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                labelMedium =
                    TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
                labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            ),
        content = content,
    )
}
