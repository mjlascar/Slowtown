package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF5C5C5C),
    error = Color(0xFFFF8A80),
    onError = Color.Black,
)

/** Blanco sobre negro y nada más: el teléfono no tiene que llamar la atención. */
@Composable
fun SlowtownTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Colors.background,
            contentColor = Colors.onBackground,
            content = content,
        )
    }
}
