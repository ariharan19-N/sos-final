package com.speaktosurvive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Pal {
    val Indigo = Color(0xFF4338CA)
    val IndigoDark = Color(0xFF1E1B4B)
    val IndigoSoft = Color(0xFFC7D2FE)
    val Bg = Color(0xFFF4F5FB)
    val Card = Color(0xFFFFFFFF)
    val Danger = Color(0xFFDC2626)
    val DangerSoft = Color(0xFFFEE2E2)
    val Ok = Color(0xFF16A34A)
    val OkSoft = Color(0xFFDCFCE7)
    val Warn = Color(0xFFD97706)
    val WarnSoft = Color(0xFFFEF3C7)
    val Muted = Color(0xFF6B7280)
    val Ink = Color(0xFF111827)
    val Off = Color(0xFF94A3B8)
}

@Composable
fun SosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pal.Indigo,
            onPrimary = Color.White,
            secondary = Pal.Ok,
            error = Pal.Danger,
            background = Pal.Bg,
            surface = Pal.Card,
            onBackground = Pal.Ink,
            onSurface = Pal.Ink
        ),
        content = content
    )
}
