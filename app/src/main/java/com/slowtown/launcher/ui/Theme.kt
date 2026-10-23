package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

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

/**
 * Las letras de la administración, un poco más chicas que las de Material: en
 * los 360 dp del S10e se veían gigantes (Marcos, 23/10). Inicio no cambia: su
 * tamaño se elige en «Cómo se ve Inicio».
 */
private val Compact: Typography = Typography().run {
    fun TextStyle.small() = copy(fontSize = fontSize * 0.86f, lineHeight = lineHeight * 0.86f)
    Typography(
        displayLarge = displayLarge.small(), displayMedium = displayMedium.small(), displaySmall = displaySmall.small(),
        headlineLarge = headlineLarge.small(), headlineMedium = headlineMedium.small(), headlineSmall = headlineSmall.small(),
        titleLarge = titleLarge.small(), titleMedium = titleMedium.small(), titleSmall = titleSmall.small(),
        bodyLarge = bodyLarge.small(), bodyMedium = bodyMedium.small(), bodySmall = bodySmall.small(),
        labelLarge = labelLarge.small(), labelMedium = labelMedium.small(), labelSmall = labelSmall.small(),
    )
}

/** Las pantallas de la administración, con [Compact]. */
@Composable
fun CompactTexts(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = Compact, content = content)
}
