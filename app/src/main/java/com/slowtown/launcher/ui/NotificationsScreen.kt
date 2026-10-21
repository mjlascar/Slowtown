package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.notif.Waves
import com.slowtown.launcher.notif.WavesListener

/**
 * Las notificaciones por oleadas ([Waves]). Está en la administración, con
 * contraseña: apagarlas o sacar una app afloja. Cada cambio se guarda en el
 * momento y se le avisa al listener, que suelta o reacomoda lo que ya retuvo.
 */
@Composable
fun NotificationsScreen(apps: List<LaunchableApp>, onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { Waves.config(context) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var times by remember { mutableStateOf(initial.times) }
    var packages by remember { mutableStateOf(initial.packages) }
    var words by remember { mutableStateOf(initial.alwaysPass) }
    val access = remember { WavesListener.hasAccess(context) }

    var hour by remember { mutableIntStateOf(9) }
    var minute by remember { mutableIntStateOf(0) }
    var timeMessage by remember { mutableStateOf<String?>(null) }
    var word by remember { mutableStateOf("") }
    var wordMessage by remember { mutableStateOf<String?>(null) }

    // Un paquete con más de un ícono (Teléfono y Contactos) va una sola vez:
    // lo que espera es el paquete.
    val choices = remember(apps) { apps.distinctBy { it.packageName } }
    val missing = (packages - choices.map { it.packageName }.toSet()).sorted()

    fun toggleEnabled(value: Boolean) {
        enabled = value
        Waves.setEnabled(context, value)
        WavesListener.reapply()
    }

    fun saveTimes(value: List<Int>) {
        Waves.setTimes(context, value)
        times = Waves.config(context).times
        WavesListener.reapply()
    }

    fun addTime() {
        val at = hour * 60 + minute
        timeMessage = if (at in times) "Ese horario ya está." else null
        if (timeMessage == null) saveTimes(times + at)
    }

    fun savePackages(value: Set<String>) {
        packages = value
        Waves.setPackages(context, value)
        WavesListener.reapply()
    }

    fun saveWords(value: List<String>) {
        words = value
        Waves.setAlwaysPass(context, value)
        WavesListener.reapply()
    }

    fun addWord() {
        val clean = Waves.cleanWord(word)
        wordMessage = when {
            clean == null -> "Escribí una palabra o un nombre."
            words.any { Waves.sameWord(it, clean) } -> "Ésa ya está."
            else -> null
        }
        if (clean != null && wordMessage == null) {
            saveWords(words + clean)
            word = ""
        }
    }

    FormScaffold(title = "Notificaciones", onBack = onBack) {
        HintText(
            "Las de las apps que elijas no llegan apenas salen: esperan y llegan " +
                "todas juntas a las horas de abajo. Llegan las originales: se tocan " +
                "y se responden como siempre.",
        )
        if (!access) {
            ErrorText(
                "Slowtown todavía no puede leer las notificaciones. El permiso se da " +
                    "por cable, con scripts/provisionar.sh.",
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch) { toggleEnabled(it) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Llegan por oleadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }

        Text("Cuándo llegan", style = MaterialTheme.typography.titleMedium)
        times.forEach { at ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Waves.format(at), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                // Siempre queda una: sin horarios no habría cuándo entregar.
                if (times.size > 1) TextButton(onClick = { saveTimes(times - at) }) { Text("Sacar") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Step("−", "Una hora menos") { hour = (hour + 23) % 24; timeMessage = null }
            Text("%02d".format(hour), style = MaterialTheme.typography.titleLarge)
            Step("+", "Una hora más") { hour = (hour + 1) % 24; timeMessage = null }
            Text(":", style = MaterialTheme.typography.titleLarge)
            Step("−", "Cinco minutos menos") { minute = (minute + 55) % 60; timeMessage = null }
            Text("%02d".format(minute), style = MaterialTheme.typography.titleLarge)
            Step("+", "Cinco minutos más") { minute = (minute + 5) % 60; timeMessage = null }
        }
        OutlinedButton(onClick = ::addTime, modifier = Modifier.fillMaxWidth()) {
            Text("Agregar las ${Waves.format(hour * 60 + minute)}")
        }
        timeMessage?.let { ErrorText(it) }

        Text("Pasan siempre", style = MaterialTheme.typography.titleMedium)
        HintText(
            "Si una notificación de esas apps trae una de estas palabras en el título " +
                "o en el texto, llega en el momento: un nombre, «urgente». Palabras " +
                "enteras, sin importar tildes ni mayúsculas: «mamá» encuentra «Mamá», " +
                "no «mamadera».",
        )
        OutlinedTextField(
            value = word,
            onValueChange = { word = it; wordMessage = null },
            label = { Text("Agregar una palabra") },
            placeholder = { Text("Mamá") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addWord() }),
            modifier = Modifier.fillMaxWidth(),
        )
        wordMessage?.let { ErrorText(it) }
        OutlinedButton(onClick = ::addWord, enabled = word.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Agregar")
        }
        words.forEach { saved ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(saved, modifier = Modifier.weight(1f))
                TextButton(onClick = { saveWords(words - saved) }) { Text("Sacar") }
            }
        }

        Text("Las apps que esperan", style = MaterialTheme.typography.titleMedium)
        HintText(
            "Pasan siempre las llamadas, las alarmas, las llamadas perdidas, la música " +
                "y lo que está en curso, como un viaje o una descarga. Una que va a " +
                "esperar puede sonar un instante antes de irse: Android avisa " +
                "después de hacerla sonar.",
        )
        if (enabled && packages.isEmpty()) HintText("Todavía no elegiste ninguna: no espera nada.")
        choices.forEach { app ->
            val checked = app.packageName in packages
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox) {
                        savePackages(if (it) packages + app.packageName else packages - app.packageName)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(app.label, style = MaterialTheme.typography.titleMedium)
            }
        }
        // Elegidas que ya no están en la lista (se desinstalaron, o las pausó
        // el nivel 2): se ven para poder sacarlas.
        missing.forEach { pkg ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(pkg, modifier = Modifier.weight(1f))
                TextButton(onClick = { savePackages(packages - pkg) }) { Text("Sacar") }
            }
        }
    }
}

/** Un botón de − o + para la hora o los minutos. */
@Composable
private fun Step(symbol: String, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}
