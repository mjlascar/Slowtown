package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.slowtown.launcher.apps.Folder

/**
 * Crear, renombrar y borrar carpetas. Vive en la configuración y no en la
 * lista de todas las apps: en el uso diario, menos botones.
 */
@Composable
fun FoldersScreen(
    folders: List<Folder>,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Folder?>(null) }

    FormScaffold(title = "Carpetas", onBack = onBack) {
        HintText(
            "Ordenan la lista de todas las apps. Para meter una app en una " +
                "carpeta, mantenela apretada en esa lista.",
        )
        OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("Nueva carpeta") }
        if (folders.isEmpty()) HintText("Todavía no hay ninguna.")
        folders.forEach { folder ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folder.name.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { renaming = folder }) { Text("Renombrar") }
                // Borrar no pierde nada: sus apps vuelven a la lista.
                TextButton(onClick = { onDelete(folder.id) }) { Text("Borrar") }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Nueva carpeta",
            initial = "",
            confirm = "Crear",
            onConfirm = {
                creating = false
                onCreate(it)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { folder ->
        NameDialog(
            title = "Renombrar",
            initial = folder.name,
            confirm = "Guardar",
            onConfirm = {
                renaming = null
                onRename(folder.id, it)
            },
            onDismiss = { renaming = null },
        )
    }
}
