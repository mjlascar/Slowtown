package com.slowtown.launcher.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Un widget que se puede poner en Inicio. */
data class WidgetChoice(val info: AppWidgetProviderInfo, val label: String, val app: String)

/** El widget de Inicio, dibujado por la app que lo provee. */
@Composable
fun WidgetSlot(host: AppWidgetHost, id: Int, info: AppWidgetProviderInfo) {
    val density = LocalDensity.current
    // El alto que pide el widget, con un piso y un techo para que Inicio siga
    // siendo Inicio.
    val height = with(density) { info.minHeight.toDp() }.coerceIn(72.dp, 280.dp)
    key(id) {
        val context = LocalContext.current
        val view = remember { host.createView(context, id, info) }
        AndroidView(
            factory = { view },
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                // Muchos widgets calculan su letra y su diseño con el tamaño
                // que les avisa el launcher: sin este aviso, el reloj de AOSP
                // se dibujaba diminuto.
                .onSizeChanged { size ->
                    val sizeDp = SizeF(size.width / density.density, size.height / density.density)
                    view.updateAppWidgetSize(Bundle(), listOf(sizeDp))
                },
        )
    }
}

@Composable
fun WidgetPickerScreen(
    choices: List<WidgetChoice>,
    hasWidget: Boolean,
    onPickHabits: () -> Unit,
    onPick: (WidgetChoice) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(title = "Widget de Inicio", onBack = onBack) {
        HintText(
            "Va abajo de todo, pegado al borde. Para que al tocarlo se abra su app, esa app " +
                "tiene que estar entre las permitidas.",
        )
        if (hasWidget) {
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Sacar el widget") }
        }
        // El de Slowtown primero: es el que viene puesto.
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onPickHabits)
                .padding(vertical = 10.dp),
        ) {
            Text("Hábitos de Slowtown", style = MaterialTheme.typography.titleMedium)
            Text(
                "Meditar y ejercicio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (choices.isEmpty()) HintText("Ninguna app instalada ofrece widgets.")
        choices.forEach { choice ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(choice) }
                    .padding(vertical = 10.dp),
            ) {
                Text(choice.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    choice.app,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
