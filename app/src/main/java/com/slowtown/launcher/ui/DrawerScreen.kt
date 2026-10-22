package com.slowtown.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.Folder
import com.slowtown.launcher.apps.Folders
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.apps.normalize

/** Lo que se puede hacer con una app manteniéndola apretada. */
class AppActions(
    val folders: List<Folder>,
    val favorites: Set<String>,
    /** Las que no se ven en la lista ni en las carpetas: aparecen sólo buscándolas. */
    val hidden: Set<String>,
    /**
     * Si antes de desinstalar pregunta Slowtown: con Device Owner desinstala
     * él, sin el diálogo de Android. Sin Device Owner pregunta Android.
     */
    val confirmsUninstall: Boolean,
    val onToggleFavorite: (LaunchableApp) -> Unit,
    /** Mueve la app a una carpeta, o la saca de la que tenga si es null. */
    val onMoveToFolder: (LaunchableApp, String?) -> Unit,
    val onToggleHidden: (LaunchableApp) -> Unit,
    val onAppInfo: (LaunchableApp) -> Unit,
    /** Si se ofrece desinstalarla: nunca a las de sistema ni a Slowtown. */
    val canUninstall: (LaunchableApp) -> Boolean,
    val onUninstall: (LaunchableApp) -> Unit,
)

/**
 * Todas las apps que se pueden abrir (con Slowtown activo, sólo las
 * permitidas). Arriba las carpetas, debajo las apps que no están en ninguna,
 * sin las ocultas. Buscando, no van las carpetas y aparecen todas las que
 * coinciden, estén donde estén, también las ocultas. Se llega deslizando para
 * arriba en Inicio.
 */
@Composable
fun DrawerScreen(
    apps: List<LaunchableApp>,
    actions: AppActions,
    onOpen: (LaunchableApp) -> Unit,
    onOpenFolder: (Folder) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val wanted = normalize(query.trim())
    val searching = wanted.isNotEmpty()
    val loose = remember(apps, actions.folders, actions.hidden) {
        val inFolders = actions.folders.flatMap { it.apps }.toSet()
        apps.filter { it.key !in inFolders && it.key !in actions.hidden }
    }
    val shown = if (searching) apps.filter { wanted in it.searchKey } else loose

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Sin teclado de entrada: se abre recién al tocar el buscador.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Buscar") },
            singleLine = true,
            // Enter abre la primera que coincide: escribir «wha» y listo.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let(onOpen) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (!searching && actions.folders.isNotEmpty()) {
                items(actions.folders.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { folder ->
                            FolderTile(folder, Modifier.weight(1f)) { onOpenFolder(folder) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            items(shown, key = { it.key }) { app ->
                // Buscando, cada app dice en qué carpeta está.
                val where = if (searching) Folders.folderOf(actions.folders, app.key)?.name else null
                AppRow(app, actions, onOpen, detail = where)
            }
        }
        if (shown.isEmpty()) {
            HintText(
                when {
                    searching -> "No hay ninguna app con ese nombre."
                    apps.isEmpty() -> "Todavía no hay apps permitidas."
                    apps.none { it.key in actions.hidden } -> "Todas las apps están en carpetas."
                    else -> "Todas las apps están en carpetas u ocultas."
                },
            )
        }
        TextButton(onClick = onBack) { Text("Volver") }
    }
}

/** Una carpeta abierta: sus apps sin las ocultas, con las mismas acciones que en la lista. */
@Composable
fun FolderScreen(
    folder: Folder,
    apps: List<LaunchableApp>,
    actions: AppActions,
    onOpen: (LaunchableApp) -> Unit,
    onBack: () -> Unit,
) {
    val inside = remember(apps, folder, actions.hidden) {
        apps.filter { it.key in folder.apps && it.key !in actions.hidden }
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(folder.name.uppercase(), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        if (inside.isEmpty()) {
            HintText(
                if (apps.none { it.key in folder.apps }) {
                    "Está vacía. Mantené apretada una app en la lista para moverla acá."
                } else {
                    "Sus apps están ocultas: aparecen buscándolas en la lista."
                },
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(inside, key = { it.key }) { app -> AppRow(app, actions, onOpen) }
        }
        TextButton(onClick = onBack) { Text("Volver") }
    }
}

@Composable
private fun FolderTile(folder: Folder, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(56.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            folder.name.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Una app de la lista. Tocándola se abre. Manteniéndola apretada: ponerla o
 * sacarla de Inicio, moverla a una de las carpetas que ya existen (crearlas es
 * de la configuración: en el uso diario, nada de botones de más), ocultarla de
 * la lista, ver su información o desinstalarla.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: LaunchableApp,
    actions: AppActions,
    onOpen: (LaunchableApp) -> Unit,
    detail: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    var dialog by remember { mutableStateOf(AppDialog.None) }
    val favorite = app.key in actions.favorites
    val hidden = app.key in actions.hidden
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(app) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dialog = AppDialog.Actions
                },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(app.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        // «en Inicio» siempre al final: la prueba del emulador la busca ahí.
        val note = listOfNotNull(detail?.uppercase(), "oculta".takeIf { hidden }, "en Inicio".takeIf { favorite })
            .joinToString(" · ")
        if (note.isNotEmpty()) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (dialog) {
        AppDialog.None -> {}
        AppDialog.Actions -> {
            val current = Folders.folderOf(actions.folders, app.key)
            // Una consulta al sistema: una vez por menú, no en cada fila.
            val removable = remember(app) { actions.canUninstall(app) }
            val uninstall: () -> Unit = {
                if (actions.confirmsUninstall) dialog = AppDialog.ConfirmUninstall else actions.onUninstall(app)
            }
            ActionsDialog(
                title = app.label,
                actions = buildList {
                    add((if (favorite) "Sacar de Inicio" else "Poner en Inicio") to { actions.onToggleFavorite(app) })
                    // Las carpetas, en un segundo diálogo: con varias, el menú se llenaba.
                    if (actions.folders.any { it.id != current?.id }) {
                        add("Mover a una carpeta" to { dialog = AppDialog.ChooseFolder })
                    }
                    if (current != null) add("Sacar de ${current.name}" to { actions.onMoveToFolder(app, null) })
                    val hide = if (hidden) "Mostrar en la lista" else "Ocultar de la lista"
                    add(hide to { actions.onToggleHidden(app) })
                    add("Información de la app" to { actions.onAppInfo(app) })
                    if (removable) add("Desinstalar" to uninstall)
                },
                onDismiss = { dialog = AppDialog.None },
            )
        }
        AppDialog.ChooseFolder -> {
            val current = Folders.folderOf(actions.folders, app.key)
            ActionsDialog(
                title = "Mover a una carpeta",
                actions = actions.folders.filter { it.id != current?.id }.map { folder ->
                    folder.name to { actions.onMoveToFolder(app, folder.id) }
                },
                onDismiss = { dialog = AppDialog.None },
            )
        }
        AppDialog.ConfirmUninstall -> ConfirmDialog(
            title = "¿Desinstalar ${app.label}?",
            text = "Se borran sus datos.",
            confirm = "Desinstalar",
            onConfirm = { actions.onUninstall(app) },
            onDismiss = { dialog = AppDialog.None },
        )
    }
}

/** El diálogo abierto de una app: de a uno por vez. */
private enum class AppDialog { None, Actions, ChooseFolder, ConfirmUninstall }
