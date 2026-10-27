package com.slowtown.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Cómo está Bedtime: prendida, de qué hora a qué hora y qué noches. */
data class BedtimeSettings(val enabled: Boolean, val start: Int, val end: Int, val days: Set<DayOfWeek>)

/**
 * Bedtime, en la administración: con contraseña, porque cambiar la hora,
 * sacar una noche o apagarla afloja.
 */
@Composable
fun BedtimeScreen(
    settings: BedtimeSettings,
    onChange: (BedtimeSettings) -> Unit,
    onBack: () -> Unit,
) {
    val (enabled, start, end, days) = settings
    FormScaffold(title = "Bedtime", onBack = onBack) {
        HintText(
            "De noche sólo se abren el reloj y las alarmas, además de llamadas, SMS y " +
                "emergencias. Para usar el teléfono, ráfagas de 5 minutos desde Inicio, sin " +
                "contraseña. Rige con Slowtown activo. Las llamadas no se cortan, tampoco " +
                "las de WhatsApp si está entre las permitidas.",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch) { onChange(settings.copy(enabled = it)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Prendida", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        TimeRow("Empieza", start, "Empieza") { onChange(settings.copy(start = it)) }
        TimeRow("Termina", end, "Termina") { onChange(settings.copy(end = it)) }
        if (start == end) ErrorText("Empieza y termina a la misma hora: así no rige nunca.")
        Text("Qué noches", style = MaterialTheme.typography.titleMedium)
        HintText("Cada noche es del día en que empieza: la del viernes va del viernes al sábado.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.values().forEach { day ->
                DayToggle(day, on = day in days, description = "Noche del ${dayName(day)}") { on ->
                    onChange(settings.copy(days = if (on) days + day else days - day))
                }
            }
        }
        if (days.isEmpty()) ErrorText("Sin ninguna noche elegida, no rige nunca.")
    }
}

/** Un día, como un círculo con su inicial: lleno si ese día rige. También lo usa Flow State. */
@Composable
internal fun DayToggle(day: DayOfWeek, on: Boolean, description: String, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .background(if (on) colors.onSurface else colors.background, CircleShape)
            .border(1.dp, if (on) colors.onSurface else colors.outline, CircleShape)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = onToggle)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "LMXJVSD"[day.value - 1].toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (on) colors.background else colors.onSurfaceVariant,
        )
    }
}

private val SPANISH = Locale.forLanguageTag("es-AR")

/** «lunes», «martes»… */
internal fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, SPANISH)

/**
 * Una hora con dos ruedas, horas y minutos, que dan la vuelta solas. [name]
 * es cómo las nombra el lector de pantalla («[name], hora»): con varios
 * horarios en la misma pantalla, tiene que decir de cuál.
 */
@Composable
internal fun TimeRow(label: String, minutes: Int, name: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        NumberWheel(
            count = 24,
            value = minutes / 60,
            label = "$name, hora",
            onValueChange = { onChange(it * 60 + minutes % 60) },
        )
        Text(":", style = MaterialTheme.typography.headlineSmall)
        NumberWheel(
            count = 60,
            value = minutes % 60,
            label = "$name, minutos",
            onValueChange = { onChange((minutes / 60) * 60 + it) },
        )
    }
}
