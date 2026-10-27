package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.admin.Limits

/** Una app con límite: cuál, de cuántos minutos por día y cuánto lleva hoy (null si no se sabe). */
data class AppLimit(val packageName: String, val label: String, val minutes: Int, val usedToday: Long?)

/**
 * Los límites por app, en la administración: con contraseña, porque subir o
 * sacar uno afloja. Cada app con su límite, de a 15 minutos, y lo que lleva hoy.
 */
@Composable
fun LimitsScreen(
    limits: List<AppLimit>,
    /** Otro límite para una app, en minutos, o null para sacárselo. */
    onChange: (packageName: String, minutes: Int?) -> Unit,
    onChooseApps: () -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(title = "Límites por app", onBack = onBack) {
        HintText(
            "Cuando una app llega a su límite del día, no se abre más hasta mañana. Cuenta " +
                "su tiempo de pantalla, como Bienestar digital. Rige con Slowtown activo.",
        )
        if (limits.isEmpty()) Text("Ninguna todavía.", style = MaterialTheme.typography.bodyLarge)
        limits.forEach { limit -> LimitRow(limit) { onChange(limit.packageName, it) } }
        OutlinedButton(onClick = onChooseApps, modifier = Modifier.fillMaxWidth()) { Text("Elegir las apps") }
    }
}

/** Una app: su nombre y «Sacar», y abajo «−  1 h  +» y lo que lleva hoy. */
@Composable
private fun LimitRow(limit: AppLimit, onChange: (Int?) -> Unit) {
    val name = limit.label
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange(null) }) { Text("Sacar") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepButton(plus = false, enabled = limit.minutes > Limits.STEP, description = "Menos tiempo: $name") {
                onChange((limit.minutes - Limits.STEP).coerceAtLeast(Limits.STEP))
            }
            Text(
                Limits.format(limit.minutes),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .widthIn(min = 76.dp)
                    // «1 h» solo no dice de qué; y al cambiar, que lo diga.
                    .clearAndSetSemantics {
                        contentDescription = "$name: ${Limits.format(limit.minutes)} por día"
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            StepButton(plus = true, enabled = limit.minutes < Limits.MAX, description = "Más tiempo: $name") {
                onChange((limit.minutes + Limits.STEP).coerceAtMost(Limits.MAX))
            }
            Spacer(Modifier.weight(1f))
            limit.usedToday?.let { used ->
                Text(
                    "Hoy: ${Limits.format((used / 60_000L).toInt())}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
