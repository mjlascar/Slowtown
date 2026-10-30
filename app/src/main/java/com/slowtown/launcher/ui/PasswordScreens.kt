package com.slowtown.launcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.security.Password
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cuántas veces quedan de «Habilitar todo» y, si no quedan, desde cuándo
 * vuelve a haber; null si no corresponde mostrarlo (Slowtown en pausa).
 */
data class FreeSessionInfo(val remaining: Int, val nextAt: String?)

/**
 * Pide la contraseña para entrar a la administración. Abajo, sin contraseña,
 * «Habilitar todo por 5 minutos» (para actualizar Waltra), con su tope.
 */
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
    freeSession: FreeSessionInfo? = null,
    onFreeSession: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy || text.isEmpty()) return
        busy = true
        val attempt = text
        scope.launch {
            val result = withContext(Dispatchers.Default) { Password.check(context, attempt) }
            busy = false
            text = ""
            when (result) {
                Password.Result.Ok -> onUnlocked()
                Password.Result.Wrong -> message = "No es esa."
                is Password.Result.Locked -> message = "Demasiados intentos. Esperá ${result.seconds} segundos."
            }
        }
    }

    FormScaffold(title = "Contraseña", onBack = onBack) {
        PasswordField(
            value = text,
            onValueChange = { text = it; message = null },
            label = "Contraseña",
            autoFocus = true,
            onDone = ::submit,
        )
        message?.let { ErrorText(it) }
        Button(onClick = ::submit, enabled = !busy && text.isNotEmpty()) { Text("Entrar") }
        if (freeSession != null) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onFreeSession,
                enabled = freeSession.remaining > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Habilitar todo por 5 minutos") }
            HintText(
                if (freeSession.remaining > 0) {
                    "Sin contraseña. Te quedan ${freeSession.remaining} de 2 en 5 horas."
                } else {
                    "Ya usaste las 2 de estas 5 horas. Vuelve a haber ${freeSession.nextAt}."
                },
            )
        }
    }
}

/** La primera vez, y para cambiarla desde la administración. */
@Composable
fun CreatePasswordScreen(onCreated: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        when {
            first.length < Password.MIN_LENGTH ->
                message = "Tiene que tener al menos ${Password.MIN_LENGTH} caracteres."
            first != second -> message = "No coinciden."
            else -> {
                busy = true
                val chosen = first
                scope.launch {
                    withContext(Dispatchers.Default) { Password.save(context, chosen) }
                    onCreated()
                }
            }
        }
    }

    FormScaffold(title = "Elegí una contraseña", onBack = onBack) {
        HintText(
            "Es la que va a pedir Slowtown para cambiar cualquier cosa. Conviene " +
                "una larga, que no te sepas de memoria, anotada en un papel que " +
                "quede en tu casa: una contraseña que sabés no frena. Si se " +
                "pierde, la salida es desinstalar Slowtown desde el modo seguro, y se " +
                "pierde lo configurado.",
        )
        PasswordField(
            value = first,
            onValueChange = { first = it; message = null },
            label = "Contraseña",
            autoFocus = true,
            imeAction = ImeAction.Next,
        )
        PasswordField(
            value = second,
            onValueChange = { second = it; message = null },
            label = "Repetila",
            onDone = ::submit,
        )
        message?.let { ErrorText(it) }
        Button(onClick = ::submit, enabled = !busy) { Text("Guardar") }
    }
}
