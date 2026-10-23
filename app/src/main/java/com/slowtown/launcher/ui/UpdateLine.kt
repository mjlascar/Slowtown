package com.slowtown.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.update.UpdateState

/**
 * La actualización de Slowtown ([com.slowtown.launcher.update.Updater]) en un
 * renglón gris. Se toca para actualizar o reintentar, y en «Instalando…», si
 * Android espera la confirmación, para volver a abrirla.
 */
@Composable
internal fun UpdateLine(state: UpdateState, onClick: () -> Unit, textAlign: TextAlign? = null) {
    val text = updateText(state) ?: return
    val tappable = when (state) {
        is UpdateState.Available, is UpdateState.NeedsPermission, is UpdateState.Failed -> true
        is UpdateState.Installing -> state.waiting
        else -> false
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = textAlign,
        modifier = Modifier
            .clickable(enabled = tappable, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

private fun updateText(state: UpdateState): String? = when (state) {
    UpdateState.None -> null
    UpdateState.Checking -> "Buscando…"
    UpdateState.UpToDate -> "Slowtown está al día."
    UpdateState.CheckFailed -> "No se pudo buscar: revisá la conexión."
    is UpdateState.Available -> "Versión ${state.version} disponible · Actualizar"
    is UpdateState.NeedsPermission -> "Permití a Slowtown instalar apps y volvé a tocar"
    // Sin corte de renglón entre el número y el por ciento.
    is UpdateState.Downloading -> "Bajando la versión ${state.version}… ${state.percent} %"
    is UpdateState.Installing -> "Instalando…"
    is UpdateState.Failed -> "No se pudo actualizar: tocá para reintentar"
}
