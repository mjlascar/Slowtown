package com.slowtown.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Cómo se ve Inicio: el tamaño de todo, en por ciento, y si va la hora grande. */
data class HomeLook(val scalePercent: Int, val showClock: Boolean)

/** De a diez, del mínimo que acepta [com.slowtown.launcher.Prefs.homeScalePercent] al de siempre. */
private val SIZES = listOf(70, 80, 90, 100)

/**
 * Cómo se ve Inicio, en la administración. El tamaño achica todo Inicio junto
 * (Marcos lo veía grande en el S10e); la lista de todas las apps y la
 * administración quedan como están. La hora grande viene apagada: la barra
 * de arriba ya la muestra.
 */
@Composable
fun HomeLookScreen(
    look: HomeLook,
    onChange: (HomeLook) -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(title = "Cómo se ve Inicio", onBack = onBack) {
        Text("Tamaño", style = MaterialTheme.typography.titleMedium)
        HintText("Achica todo junto: las letras, los márgenes y los hábitos.")
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SIZES.forEach { percent ->
                SizeChoice(percent, selected = percent == look.scalePercent, modifier = Modifier.weight(1f)) {
                    onChange(look.copy(scalePercent = percent))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = look.showClock, role = Role.Switch) { onChange(look.copy(showClock = it)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("La hora grande", style = MaterialTheme.typography.titleMedium)
                HintText("Con la hora grande, la de la barra de arriba se esconde en Inicio.")
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = look.showClock, onCheckedChange = null)
        }
        Text("Así se ve", style = MaterialTheme.typography.titleMedium)
        Preview(look)
    }
}

/**
 * Un pedazo de Inicio al tamaño elegido, para probar tamaños acá mismo: volver
 * a Inicio a mirar y entrar de nuevo pediría la contraseña cada vez.
 */
@Composable
private fun Preview(look: HomeLook) {
    val base = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clearAndSetSemantics { contentDescription = "Vista previa de Inicio" }
            .padding(16.dp),
    ) {
        CompositionLocalProvider(LocalDensity provides Density(base.density * look.scalePercent / 100f, base.fontScale)) {
            Column {
                HomeHeader(showClock = look.showClock, screenTime = "44 min", onLongPress = {})
                Spacer(Modifier.height(24.dp))
                listOf("Teléfono", "WhatsApp").forEach {
                    Text(it, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

/** Un tamaño: lleno el elegido, como las noches de Bedtime. */
@Composable
private fun SizeChoice(percent: Int, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.onSurface else colors.background)
            .border(1.dp, if (selected) colors.onSurface else colors.outline, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$percent %",
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.background else colors.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}
