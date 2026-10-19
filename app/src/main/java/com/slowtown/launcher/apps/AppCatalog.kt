package com.slowtown.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.text.Collator
import java.util.Locale

data class LaunchableApp(val label: String, val component: ComponentName) {
    val key: String get() = component.flattenToString()
    val packageName: String get() = component.packageName

    fun intent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

object AppCatalog {
    private val collator = Collator.getInstance(Locale.forLanguageTag("es-AR"))

    /**
     * No se pueden poner en Inicio: son las puertas que la contraseña cierra.
     * Se abren desde la administración, por una sesión que termina sola.
     */
    const val PLAY_STORE = "com.android.vending"
    private val ADMIN_ONLY = setOf(
        "com.android.settings",
        PLAY_STORE,
        "com.sec.android.app.samsungapps",
    )

    /** Lo que se puede elegir para Inicio, ordenado como en un diccionario. */
    // La variante con ResolveInfoFlags recién existe en API 33; el S10e es API 31.
    @Suppress("DEPRECATION")
    fun launchable(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .map {
                LaunchableApp(
                    label = it.loadLabel(pm).toString(),
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                )
            }
            .filter { it.packageName != context.packageName && it.packageName !in ADMIN_ONLY }
            .sortedWith(compareBy(collator) { it.label })
    }
}
