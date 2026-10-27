package com.slowtown.launcher.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.admin.FlowState
import com.slowtown.launcher.admin.FlowWindow
import java.time.DayOfWeek

/** Cómo está Flow State: prendido, sus horarios y qué apps frena (como paquetes). */
data class FlowSettings(val enabled: Boolean, val windows: List<FlowWindow>, val apps: Set<String>)

/**
 * Flow State, en la administración: con contraseña, como Bedtime, porque
 * achicar un horario o sacar una app afloja. Varios horarios, cada uno con sus
 * días; las apps son las mismas en todos.
 */
@Composable
fun FlowStateScreen(
    settings: FlowSettings,
    /** Los nombres de las apps elegidas, para mostrarlas. */
    appNames: List<String>,
    onChange: (FlowSettings) -> Unit,
    onChooseApps: () -> Unit,
    onBack: () -> Unit,
) {
    val (enabled, windows, apps) = settings
    FormScaffold(title = "Flow State", onBack = onBack) {
        HintText(
            "En sus horarios no se abren las apps que elijas, para concentrarte. Para usar " +
                "una igual, ráfagas de 5 minutos desde Inicio, sin contraseña. Rige con " +
                "Slowtown activo, y las llamadas no se cortan.",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch) { onChange(settings.copy(enabled = it)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Prendido", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }

        Text("Horarios", style = MaterialTheme.typography.titleMedium)
        windows.forEachIndexed { i, window ->
            WindowCard(
                number = i + 1,
                window = window,
                onChange = { changed -> onChange(settings.copy(windows = windows.toMutableList().also { it[i] = changed })) },
                onRemove = { onChange(settings.copy(windows = windows.filterIndexed { j, _ -> j != i })) },
            )
        }
        if (windows.isEmpty()) ErrorText("Sin horarios, no rige nunca.")
        OutlinedButton(
            onClick = { onChange(settings.copy(windows = windows + nextWindow(windows))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sumar un horario") }

        Text("Qué apps", style = MaterialTheme.typography.titleMedium)
        if (apps.isEmpty()) {
            ErrorText("Sin apps elegidas, no frena nada.")
        } else {
            Text(appNames.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
        }
        OutlinedButton(onClick = onChooseApps, modifier = Modifier.fillMaxWidth()) { Text("Elegir las apps") }
    }
}

/** El horario que se suma: el de entrada si no hay ninguno, y si no, la tarde de lunes a viernes. */
private fun nextWindow(windows: List<FlowWindow>): FlowWindow =
    if (windows.isEmpty()) FlowState.DEFAULT_WINDOWS.first() else FlowWindow(14 * 60, 18 * 60, WEEKDAYS)

private val WEEKDAYS = DayOfWeek.entries.take(5).toSet()

/** Un horario, en su recuadro: de qué hora a qué hora, qué días, y sacarlo. */
@Composable
private fun WindowCard(number: Int, window: FlowWindow, onChange: (FlowWindow) -> Unit, onRemove: () -> Unit) {
    val name = "Horario $number"
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Sacar") }
        }
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeRow("Empieza", window.start, "$name, empieza") { onChange(window.copy(start = it)) }
            TimeRow("Termina", window.end, "$name, termina") { onChange(window.copy(end = it)) }
            if (window.start == window.end) ErrorText("Empieza y termina a la misma hora: así no rige nunca.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DayOfWeek.entries.forEach { day ->
                    DayToggle(day, on = day in window.days, description = "$name: ${dayName(day)}") { on ->
                        onChange(window.copy(days = if (on) window.days + day else window.days - day))
                    }
                }
            }
            if (window.days.isEmpty()) ErrorText("Sin ningún día elegido, no rige nunca.")
        }
    }
}
