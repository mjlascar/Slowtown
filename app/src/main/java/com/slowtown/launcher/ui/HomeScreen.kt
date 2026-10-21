package com.slowtown.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.LaunchableApp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hora, fecha, las apps de Inicio en texto y, abajo de todo, el widget si hay uno.
 * Manteniendo apretada la hora se abre la administración; deslizando para
 * arriba, todas las apps.
 *
 * @param apps las apps de Inicio, o null mientras se cargan (para no mostrar
 *   el cartel de ayuda un instante en cada vuelta a Inicio).
 * @param hint qué decir si no hay ninguna.
 */
@Composable
fun HomeScreen(
    apps: List<LaunchableApp>?,
    hint: String,
    safeMode: Boolean,
    /** Un aviso corto abajo de la fecha, por ejemplo «Todo habilitado hasta las 17:52». */
    notice: String?,
    /**
     * De noche, la ráfaga de 5 minutos con las apps de siempre: Inicio dice
     * «Es Bedtime, andá a dormir», y el botón aparece recién al tocarlo.
     */
    onBurst: (() -> Unit)?,
    /** El tiempo de pantalla de hoy, corto («1 h 5 min»), o null sin acceso a los datos de uso. */
    screenTime: String?,
    widget: (@Composable () -> Unit)?,
    onOpen: (LaunchableApp) -> Unit,
    onAdmin: () -> Unit,
    onDrawer: () -> Unit,
) {
    var dragged by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -threshold) onDrawer() },
                ) { _, amount -> dragged += amount }
            }
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
    ) {
        Clock(onLongPress = onAdmin, screenTime = screenTime)
        if (notice != null) {
            Spacer(Modifier.height(12.dp))
            Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        if (onBurst != null) {
            // La ráfaga no está siempre a la vista (lo pidió Marcos): hay que
            // tocar el aviso. Al irse de Inicio o al terminar la noche, se esconde.
            var burstShown by remember { mutableStateOf(false) }
            Spacer(Modifier.height(12.dp))
            Text(
                BEDTIME_NOTICE,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .clickable { burstShown = !burstShown }
                    .padding(vertical = 4.dp),
            )
            if (burstShown) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBurst) { Text("Usar 5 minutos") }
            }
        }
        if (safeMode) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Slowtown se colgó varias veces seguidas y se pausó solo. " +
                    "Mantené apretada la hora para revisarlo.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(40.dp))
        if (apps != null && apps.isEmpty()) {
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        // Una columna común y no una lista con scroll: así deslizar para arriba
        // en cualquier lado abre todas las apps, sin pelearse con el scroll.
        apps.orEmpty().forEach { app ->
            Text(
                app.label,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(app) }
                    .padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "↑ todas las apps",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(onClick = onDrawer)
                .padding(12.dp),
        )
        // El widget va pegado al borde de abajo (lo pidió Marcos): arriba
        // queda la hora y las apps, como en un teléfono de teclas.
        if (widget != null) {
            Spacer(Modifier.height(8.dp))
            widget()
        }
    }
}

/**
 * La hora y la fecha. Arriba a la derecha, el tiempo de pantalla de hoy, sin
 * explicación (lo pidió Marcos: menos texto, y la cabecera más pareja); la
 * explicación queda para quien lee la pantalla en voz alta.
 */
@Composable
private fun Clock(onLongPress: () -> Unit, screenTime: String?) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            // Despertar justo cuando cambia el minuto, no cada segundo.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Column(Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(now.format(HOUR), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.weight(1f))
            if (screenTime != null) {
                Text(
                    screenTime,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleLarge,
                    // A la altura de lo alto de los números de la hora.
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .semantics { contentDescription = "Hoy: $screenTime de pantalla" },
                )
            }
        }
        Text(
            now.format(DATE).replaceFirstChar { it.titlecase(SPANISH) },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SPANISH = Locale.forLanguageTag("es-AR")

private val BEDTIME_NOTICE = buildAnnotatedString {
    append("Es ")
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Bedtime") }
    append(", andá a dormir")
}
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
