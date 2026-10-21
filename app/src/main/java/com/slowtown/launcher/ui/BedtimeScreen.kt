package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * La hora de dormir, en la administración: con contraseña, porque cambiar la
 * hora o apagarla afloja.
 */
@Composable
fun BedtimeScreen(
    enabled: Boolean,
    start: Int,
    end: Int,
    onChange: (enabled: Boolean, start: Int, end: Int) -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(title = "Hora de dormir", onBack = onBack) {
        HintText(
            "De noche sólo se abren el reloj y las alarmas, además de llamadas, SMS y " +
                "emergencias. Para usar el teléfono, ráfagas de 5 minutos desde Inicio, sin " +
                "contraseña. Rige con Slowtown activo.",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch) { onChange(it, start, end) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Prendida", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        TimeRow("Empieza", start) { onChange(enabled, it, end) }
        TimeRow("Termina", end) { onChange(enabled, start, it) }
        if (start == end) ErrorText("Empieza y termina a la misma hora: así no rige nunca.")
    }
}

/** Una hora con − y +, de a 15 minutos, dando la vuelta a la medianoche. */
@Composable
private fun TimeRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    fun step(delta: Int) = onChange(((minutes + delta) % DAY + DAY) % DAY)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { step(-15) },
            modifier = Modifier.semantics { contentDescription = "Más temprano: $label" },
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(dayTime(minutes), style = MaterialTheme.typography.titleLarge)
        TextButton(
            onClick = { step(15) },
            modifier = Modifier.semantics { contentDescription = "Más tarde: $label" },
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

private const val DAY = 24 * 60

fun dayTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
