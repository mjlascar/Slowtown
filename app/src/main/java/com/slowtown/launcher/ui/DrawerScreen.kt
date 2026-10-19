package com.slowtown.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.apps.normalize

/**
 * Todas las apps que se pueden abrir (con Slowtown activo, sólo las
 * permitidas), con buscador. Se llega deslizando para arriba en Inicio.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerScreen(
    apps: List<LaunchableApp>,
    favorites: Set<String>,
    onOpen: (LaunchableApp) -> Unit,
    onToggleFavorite: (LaunchableApp) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    val matches = remember(apps, query) {
        val wanted = normalize(query.trim())
        if (wanted.isEmpty()) apps else apps.filter { wanted in it.searchKey }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Buscar") },
            singleLine = true,
            // Enter abre la primera que coincide: escribir «wha» y listo.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { matches.firstOrNull()?.let(onOpen) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
        Spacer(Modifier.height(8.dp))
        HintText("Mantené apretada una app para ponerla o sacarla de Inicio.")
        Spacer(Modifier.height(8.dp))
        if (matches.isEmpty()) {
            HintText(if (apps.isEmpty()) "Todavía no hay apps permitidas." else "No hay ninguna app con ese nombre.")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(matches, key = { it.key }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpen(app) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleFavorite(app)
                            },
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (app.key in favorites) {
                        Text(
                            "en Inicio",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Volver") }
    }

    // El teclado aparece solo: esta pantalla es para buscar.
    LaunchedEffect(Unit) { focus.requestFocus() }
}
