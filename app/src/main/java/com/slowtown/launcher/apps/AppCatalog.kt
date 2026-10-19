package com.slowtown.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class LaunchableApp(val label: String, val component: ComponentName) {
    val key: String get() = component.flattenToString()
    val packageName: String get() = component.packageName

    /** El nombre sin tildes ni mayúsculas, para buscar: «cámara» encuentra «Camara». */
    val searchKey: String = normalize(label)

    fun intent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** Sin tildes ni mayúsculas. */
fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

object AppCatalog {
    private val collator = Collator.getInstance(Locale.forLanguageTag("es-AR"))

    /**
     * No se pueden permitir: son las puertas que la contraseña cierra. Se
     * abren desde la administración, por una sesión que termina sola.
     */
    const val PLAY_STORE = "com.android.vending"
    private val ADMIN_ONLY = setOf(
        "com.android.settings",
        PLAY_STORE,
        "com.sec.android.app.samsungapps",
    )

    /** Todo lo que tiene ícono, menos Slowtown, ordenado como en un diccionario. */
    // La variante con ResolveInfoFlags recién existe en API 33; el S10e es API 31.
    @Suppress("DEPRECATION")
    fun all(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .map {
                LaunchableApp(
                    label = it.loadLabel(pm).toString(),
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                )
            }
            .filter { it.packageName != context.packageName }
            .sortedWith(compareBy(collator) { it.label })
    }

    /** Lo que se puede permitir: todo menos las puertas de la administración. */
    fun permittable(apps: List<LaunchableApp>): List<LaunchableApp> =
        apps.filter { it.packageName !in ADMIN_ONLY }
}
