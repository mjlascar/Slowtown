package com.slowtown.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * El esqueleto de las pantallas de administración: arriba y fijos, la flecha
 * para volver y el título; abajo, el contenido, que se desliza. «Volver»
 * estaba al pie, y en las pantallas largas (Widget, Notificaciones) había que
 * bajar hasta el final para irse.
 */
@Composable
internal fun FormScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = CompactTexts {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        BackBar(title, onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** La flecha para volver («Volver» para el lector de pantalla) y el título, en un renglón. */
@Composable
internal fun BackBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // El botón mide 48 y la flecha 24: corrido 12 hacia el borde, el
            // dibujo queda alineado con el texto de abajo, a 28.
            .padding(start = 16.dp, end = 28.dp, top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) {
            BackArrow(Modifier.size(24.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
    }
}

/** La flecha de volver, dibujada como la de Material: sin sumar la biblioteca de íconos. */
@Composable
private fun BackArrow(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        // En un cuadrado de 24: la punta a la izquierda y la raya hasta la derecha.
        fun at(x: Float, y: Float) = Offset(size.width * x / 24f, size.height * y / 24f)
        val stroke = 2.dp.toPx()
        drawLine(color, at(5f, 12f), at(19f, 12f), stroke, StrokeCap.Round)
        drawLine(color, at(5f, 12f), at(12f, 5f), stroke, StrokeCap.Round)
        drawLine(color, at(5f, 12f), at(12f, 19f), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun HintText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}
