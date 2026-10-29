package com.slowtown.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.LaunchableApp

/**
 * Elegir apps de una lista, con casillas: las bloqueadas, las de Bedtime, las
 * de Flow State y las de los límites por app, cada una con su título y su
 * explicación.
 */
@Composable
fun ChooseAppsScreen(
    apps: List<LaunchableApp>,
    chosen: Set<String>,
    onDone: (Set<String>) -> Unit,
    onBack: () -> Unit,
    title: String,
    hint: String,
) {
    var selection by remember(chosen) { mutableStateOf(chosen) }

    CompactTexts {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // «Volver» arriba, como en las demás pantallas de la administración.
            BackBar(title, onBack)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            ) {
                HintText(hint)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(apps, key = { it.key }) { app ->
                        val checked = app.key in selection
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selection = if (checked) selection - app.key else selection + app.key }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                Button(onClick = { onDone(selection) }, modifier = Modifier.align(Alignment.End)) {
                    Text("Listo (${apps.count { it.key in selection }})")
                }
            }
        }
    }
}
