package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

data class AdminStatus(
    val deviceOwner: Boolean = false,
    /** Lo que eligió el usuario. */
    val closed: Boolean = false,
    /** Lo que pasa ahora: si el sistema está en lock task. */
    val locked: Boolean = false,
    val safeMode: Boolean = false,
)

@Composable
fun AdminScreen(
    status: AdminStatus,
    onChooseApps: () -> Unit,
    onToggleClosed: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onChangePassword: () -> Unit,
    onLeaveSafeMode: () -> Unit,
    onRelease: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmRelease by remember { mutableStateOf(false) }
    val wide = Modifier.fillMaxWidth()

    FormScaffold(title = "Administración", onBack = onBack) {
        StatusLine("Device Owner", if (status.deviceOwner) "sí" else "no")
        StatusLine(
            "Slowtown",
            when {
                status.locked -> "activo"
                status.closed -> "se activa al volver a Inicio"
                else -> "en pausa"
            },
        )
        if (!status.deviceOwner) {
            HintText(
                "Sin Device Owner, Slowtown es sólo un launcher: el panel de " +
                    "ajustes rápidos y Ajustes quedan a mano. Se activa por cable " +
                    "con scripts/provisionar.sh.",
            )
        }
        if (status.safeMode) {
            ErrorText("Modo a prueba de fallos: Slowtown se colgó varias veces seguidas y se pausó solo.")
            OutlinedButton(onClick = onLeaveSafeMode, modifier = wide) {
                Text("Ya lo revisé: salir del modo a prueba de fallos")
            }
        }

        Button(onClick = onChooseApps, modifier = wide) { Text("Elegir las apps de Inicio") }
        Button(onClick = onToggleClosed, enabled = status.deviceOwner, modifier = wide) {
            Text(if (status.closed) "Pausar Slowtown" else "Activar Slowtown")
        }
        if (status.closed) HintText("Pausarlo cierra las apps que estén abiertas.")
        OutlinedButton(onClick = onOpenSettings, modifier = wide) { Text("Ir a Ajustes") }
        OutlinedButton(onClick = onOpenPlayStore, modifier = wide) { Text("Ir a Play Store") }
        HintText(
            "Ajustes y Play Store dejan todo el teléfono a mano hasta que vuelvas " +
                "a Inicio, se apague la pantalla o pasen 10 minutos.",
        )
        OutlinedButton(onClick = onChangePassword, modifier = wide) { Text("Cambiar la contraseña") }

        if (status.deviceOwner) {
            OutlinedButton(
                onClick = { confirmRelease = true },
                modifier = wide,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Dejar de ser Device Owner") }
        }
    }

    if (confirmRelease) {
        AlertDialog(
            onDismissRequest = { confirmRelease = false },
            title = { Text("¿Dejar de ser Device Owner?") },
            text = {
                Text(
                    "Slowtown queda como un launcher común, sin poder activarse. Para " +
                        "volver hay que provisionar otra vez por cable, y Android sólo " +
                        "lo deja hacer sin ninguna cuenta en el teléfono.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRelease = false; onRelease() }) { Text("Sí, soltarlo") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRelease = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
