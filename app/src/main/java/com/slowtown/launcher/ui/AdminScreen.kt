package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.slowtown.launcher.update.UpdateState

data class AdminStatus(
    val deviceOwner: Boolean = false,
    /** Lo que eligió el usuario. */
    val closed: Boolean = false,
    /** Lo que pasa ahora: si el sistema está en lock task. */
    val locked: Boolean = false,
    val safeMode: Boolean = false,
    /** Sin cable, lo que necesita la guardia: el acceso a los datos de uso y ser el Inicio. */
    val usageAccess: Boolean = false,
    val home: Boolean = false,
) {
    /** Si Slowtown puede cerrar el teléfono: con Device Owner, o sin cable con la guardia. */
    val canClose: Boolean get() = deviceOwner || (usageAccess && home)
}

@Composable
fun AdminScreen(
    status: AdminStatus,
    /** La versión instalada, «0.2.1». */
    version: String,
    /** Si se actualiza sola desde GitHub: sólo la de las releases. La de cable, por cable. */
    updates: Boolean,
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    onUpdate: () -> Unit,
    onChooseApps: () -> Unit,
    onSites: () -> Unit,
    onChooseWidget: () -> Unit,
    onHomeLook: () -> Unit,
    onFolders: () -> Unit,
    onBedtime: () -> Unit,
    onFlowState: () -> Unit,
    onLimits: () -> Unit,
    onNotifications: () -> Unit,
    onUsageAccess: () -> Unit,
    onChooseHome: () -> Unit,
    onToggleClosed: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onChangePassword: () -> Unit,
    onLeaveSafeMode: () -> Unit,
    onBack: () -> Unit,
) {
    val wide = Modifier.fillMaxWidth()

    FormScaffold(title = "Administración", onBack = onBack) {
        StatusLine("Device Owner", if (status.deviceOwner) "sí" else "no")
        StatusLine(
            "Slowtown",
            when {
                status.locked -> "activo"
                status.closed && status.deviceOwner -> "se activa al volver a Inicio"
                status.closed && status.canClose && !status.safeMode -> "activo, sin cable"
                else -> "en pausa"
            },
        )
        if (!status.deviceOwner) {
            HintText(
                "Sin Device Owner, Slowtown cierra sin cable: si se abre algo que no corresponde, " +
                    "vuelve a Inicio. Se esquiva más fácil (con el modo seguro, por ejemplo); el " +
                    "cierre completo se activa por cable, con scripts/provisionar.sh.",
            )
            if (!status.usageAccess) {
                ErrorText("Para cerrar sin cable falta el acceso a los datos de uso.")
                OutlinedButton(onClick = onUsageAccess, modifier = wide) { Text("Dar el acceso a los datos de uso") }
            }
            if (!status.home) {
                ErrorText("Para cerrar sin cable, Slowtown tiene que ser el Inicio.")
                OutlinedButton(onClick = onChooseHome, modifier = wide) { Text("Elegir Slowtown como Inicio") }
            }
        }
        if (status.safeMode) {
            ErrorText("Modo a prueba de fallos: Slowtown se colgó varias veces seguidas y se pausó solo.")
            OutlinedButton(onClick = onLeaveSafeMode, modifier = wide) {
                Text("Ya lo revisé: salir del modo a prueba de fallos")
            }
        }

        Button(onClick = onChooseApps, modifier = wide) { Text("Elegir las apps bloqueadas") }
        OutlinedButton(onClick = onSites, modifier = wide) { Text("Páginas bloqueadas") }
        OutlinedButton(onClick = onChooseWidget, modifier = wide) { Text("Elegir el widget de Inicio") }
        OutlinedButton(onClick = onHomeLook, modifier = wide) { Text("Cómo se ve Inicio") }
        OutlinedButton(onClick = onFolders, modifier = wide) { Text("Carpetas") }
        OutlinedButton(onClick = onBedtime, modifier = wide) { Text("Bedtime") }
        OutlinedButton(onClick = onFlowState, modifier = wide) { Text("Flow State") }
        OutlinedButton(onClick = onLimits, modifier = wide) { Text("Límites por app") }
        OutlinedButton(onClick = onNotifications, modifier = wide) { Text("Notificaciones") }
        Button(onClick = onToggleClosed, enabled = status.closed || status.canClose, modifier = wide) {
            Text(if (status.closed) "Pausar Slowtown" else "Activar Slowtown")
        }
        // Sin Device Owner no hay lock task que cierre nada al salir, ni nivel 2.
        if (status.closed && status.deviceOwner) {
            HintText("Pausarlo cierra las apps que estén abiertas. Lo que está bloqueado siempre sigue bloqueado.")
        }
        OutlinedButton(onClick = onOpenSettings, modifier = wide) { Text("Ir a Ajustes") }
        OutlinedButton(onClick = onOpenPlayStore, modifier = wide) { Text("Ir a Play Store") }
        HintText(
            "Ajustes y Play Store dejan a mano todo el teléfono, salvo lo que está " +
                "bloqueado siempre, hasta que vuelvas a Inicio, se apague la pantalla " +
                "o pasen 10 minutos.",
        )
        OutlinedButton(onClick = onChangePassword, modifier = wide) { Text("Cambiar la contraseña") }

        if (status.deviceOwner) {
            // Desde la administración ya no: la contraseña está en un cajón de
            // la casa, y soltarlo apagaba también lo que se bloquea siempre.
            HintText("Slowtown deja de ser Device Owner sólo por cable, desde la compu (scripts/liberar.sh).")
        }

        StatusLine("Versión", version)
        if (updates) {
            val busy = update == UpdateState.Checking ||
                update is UpdateState.Downloading ||
                update is UpdateState.Installing
            OutlinedButton(onClick = onCheckUpdate, enabled = !busy, modifier = wide) { Text("Buscar actualización") }
            UpdateLine(update, onUpdate)
            // Lo que dijeron Android o la red: sin cable, es la única forma de saber qué pasó.
            (update as? UpdateState.Failed)?.detail?.let { HintText(it.take(300)) }
        } else {
            HintText("Versión de cable: se actualiza por cable.")
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
