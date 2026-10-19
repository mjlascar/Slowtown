package com.slowtown.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.LaunchableApp

@Composable
fun ChooseAppsScreen(
    apps: List<LaunchableApp>,
    chosen: Set<String>,
    onDone: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var selection by remember(chosen) { mutableStateOf(chosen) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Apps de Inicio", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        HintText(
            "Con Slowtown activo, las que no marques no se abren. " +
                "Las llamadas y los SMS entran igual.",
        )
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Volver") }
            Button(onClick = { onDone(selection) }) {
                Text("Listo (${apps.count { it.key in selection }})")
            }
        }
    }
}
