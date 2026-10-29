package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    var groupsOnly by remember { mutableStateOf(initial.groupsOnly) }
    var words by remember { mutableStateOf(initial.alwaysPass) }
    val access = remember { WavesListener.hasAccess(context) }
    val groupApps = remember { Waves.groupApps(context) }

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
        // La que deja de esperar no se queda con «sólo los grupos»: si vuelve, espera entera.
        val kept = groupsOnly intersect value
        if (kept != groupsOnly) {
            groupsOnly = kept
            Waves.setGroupsOnly(context, kept)
        }
        WavesListener.reapply()
    }

    fun saveGroupsOnly(value: Set<String>) {
        groupsOnly = value
        Waves.setGroupsOnly(context, value)
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
            "Las notificaciones de las apps que marques abajo no te interrumpen: se guardan " +
                "y llegan todas juntas, en silencio, a las horas que elijas. Después se abren " +
                "y se responden como siempre.",
        )
        if (!access) {
            ErrorText(
                "Slowtown todavía no puede leer las notificaciones. Dale el permiso en " +
                    "Ajustes, en «Acceso a notificaciones», o por cable con provisionar.sh.",
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberWheel(
                count = 24,
                value = hour,
                onValueChange = { hour = it; timeMessage = null },
                label = "Nueva entrega, hora",
            )
            Text(":", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
            NumberWheel(
                count = 60,
                value = minute,
                onValueChange = { minute = it; timeMessage = null },
                label = "Nueva entrega, minutos",
            )
        }
        OutlinedButton(onClick = ::addTime, modifier = Modifier.fillMaxWidth()) {
            Text("Agregar las ${Waves.format(hour * 60 + minute)}")
        }
        timeMessage?.let { ErrorText(it) }

        Text("Pasan siempre", style = MaterialTheme.typography.titleMedium)
        HintText(
            "Si una notificación trae una de estas palabras, llega en el momento: " +
                "por ejemplo, un nombre o «urgente». Cuenta la palabra entera: «mamá» " +
                "encuentra «Mamá», pero no «mamadera».",
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

        // «Qué apps esperan» no se entendía (lo dijo Marcos el 29/10).
        Text("Apps que avisan a horario", style = MaterialTheme.typography.titleMedium)
        HintText(
            "Marcá las que no querés que te interrumpan: sus notificaciones se guardan y te " +
                "llegan a las horas de arriba. Las llamadas, las alarmas, la música y lo que " +
                "está en curso (un viaje, una descarga) llegan igual en el momento. A veces " +
                "una suena un instante antes de guardarse.",
        )
        if (enabled && packages.isEmpty()) HintText("Todavía no marcaste ninguna: llegan todas en el momento.")
        choices.forEach { app ->
            val checked = app.packageName in packages
            // En una columna, para que «sólo los grupos» quede pegado a su app.
            Column {
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
                // Sólo en WhatsApp, la única en la que se reconoce bien un grupo
                // (y en otra que ya lo tenía, para poder sacarlo).
                if (checked && (app.packageName in groupApps || app.packageName in groupsOnly)) {
                    val onlyGroups = app.packageName in groupsOnly
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // Debajo del nombre de la app: la casilla mide 24 dp, más los 12 del espacio.
                            .padding(start = 36.dp)
                            .toggleable(value = onlyGroups, role = Role.Checkbox) {
                                saveGroupsOnly(if (it) groupsOnly + app.packageName else groupsOnly - app.packageName)
                            }
                            .semantics { contentDescription = "Sólo los grupos: ${app.label}" }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = onlyGroups, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Sólo los grupos", style = MaterialTheme.typography.bodyLarge)
                            HintText("Los chats de a dos llegan en el momento.")
                        }
                    }
                }
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
