package com.slowtown.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.LaunchableApp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hora, fecha y la lista de apps elegidas, en texto. La administración se
 * abre manteniendo apretada la hora.
 *
 * @param apps las apps de Inicio, o null mientras se cargan (para no mostrar
 *   el cartel de «elegí tus apps» un instante en cada vuelta a Inicio).
 */
@Composable
fun HomeScreen(
    apps: List<LaunchableApp>?,
    safeMode: Boolean,
    onOpen: (LaunchableApp) -> Unit,
    onAdmin: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
    ) {
        Clock(onLongPress = onAdmin)
        if (safeMode) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Slowtown se colgó varias veces seguidas y se pausó solo. " +
                    "Mantené apretada la hora para revisarlo.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(48.dp))
        if (apps != null && apps.isEmpty()) {
            Text(
                "Mantené apretada la hora para elegir las apps.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn {
            items(apps.orEmpty(), key = { it.key }) { app ->
                Text(
                    app.label,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(app) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Clock(onLongPress: () -> Unit) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            // Despertar justo cuando cambia el minuto, no cada segundo.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Column(Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }) {
        Text(now.format(HOUR), style = MaterialTheme.typography.displayLarge)
        Text(
            now.format(DATE).replaceFirstChar { it.titlecase(SPANISH) },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SPANISH = Locale.forLanguageTag("es-AR")
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
