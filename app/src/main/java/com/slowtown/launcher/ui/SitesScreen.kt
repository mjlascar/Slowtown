package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.slowtown.launcher.admin.Chrome
import com.slowtown.launcher.admin.Fixed

/**
 * Las páginas que Chrome no abre. Las que se suman acá son el nivel 1 (con la
 * contraseña, porque ya se está en la administración); de las fijas, el nivel
 * 2, se dice sólo cuántas son, y no se tocan.
 */
@Composable
fun SitesScreen(
    sites: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val fixedCount = remember { (Fixed.CORE_SITES + Fixed.listedSites(context)).distinct().size }

    fun add() {
        val site = Chrome.normalizeSite(text)
        message = when {
            site == null -> "Eso no parece una página. Probá con algo como 9gag.com."
            Fixed.covers(context, site) -> "Ésa ya está bloqueada siempre."
            site in sites -> "Ésa ya está en tu lista."
            else -> null
        }
        if (site != null && message == null) {
            onAdd(site)
            text = ""
        }
    }

    FormScaffold(title = "Páginas bloqueadas", onBack = onBack) {
        HintText("Chrome no las abre, ni a sus subdominios: bloquear 9gag.com bloquea también m.9gag.com.")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; message = null },
            label = { Text("Agregar una página") },
            placeholder = { Text("9gag.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let { ErrorText(it) }
        OutlinedButton(onClick = ::add, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Agregar")
        }

        Text("Las tuyas", style = MaterialTheme.typography.titleMedium)
        if (sites.isEmpty()) HintText("Todavía no sumaste ninguna.")
        sites.sorted().forEach { site ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(site, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(site) }) { Text("Sacar") }
            }
        }

        Text("Siempre bloqueadas", style = MaterialTheme.typography.titleMedium)
        // Sin nombrar qué bloquea (lo pidió Marcos): quien navega se da cuenta solo.
        HintText(
            "Son $fixedCount. No se cambian desde acá, ni con la contraseña: sólo " +
                "con una versión nueva de Slowtown, instalada por cable desde la compu. " +
                "Para saber si una página ya está, probá agregarla.",
        )
    }
}
