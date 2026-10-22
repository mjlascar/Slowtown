package com.slowtown.launcher.apps

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
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

    const val PLAY_STORE = "com.android.vending"

    /**
     * No se pueden permitir: son las puertas que la contraseña cierra. Se
     * abren desde la administración, por una sesión que termina sola. La Play
     * Store sí se puede permitir (lo decidió Marcos el 22/10, para bajar una
     * app fuera de casa): lo que se instala no abre hasta permitirlo, y los
     * navegadores y lo del nivel 2 se pausan apenas se instalan.
     */
    private val ADMIN_ONLY = setOf(
        "com.android.settings",
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

    /** La información de la app, que es una pantalla de Ajustes. */
    fun infoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** El diálogo de Android para desinstalarla. Sin NEW_TASK: se abre esperando el resultado. */
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))

    /**
     * Si se ofrece desinstalarla. Las de sistema nunca, tampoco las
     * actualizadas: para ésas Android ofrece «Desinstalar actualizaciones»,
     * que rearma el paquete y le borra la pausa del nivel 2. Ni Slowtown, ni
     * lo que Slowtown trabó (Chrome).
     */
    // La variante con ApplicationInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    fun canUninstall(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return false
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false
        // Sin administrador (null) lo puede preguntar cualquiera, también sin Device Owner.
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return !runCatching { dpm.isUninstallBlocked(null, packageName) }.getOrDefault(false)
    }

    /**
     * Desinstala sin el diálogo de Android, que con Slowtown activo quedaría
     * bloqueado: el instalador no está en la lista de lock task. Sólo lo puede
     * hacer el Device Owner, y antes pregunta Slowtown. Android deja una
     * notificación de que la borró el administrador. [onDone] dice si anduvo,
     * en el hilo principal.
     */
    // RECEIVER_NOT_EXPORTED es de API 33; en Android 12 el sistema lo ignora.
    @SuppressLint("InlinedApi")
    fun uninstall(context: Context, packageName: String, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        // Una acción por paquete, así dos desinstalaciones seguidas no se pisan.
        val action = "${app.packageName}.DESINSTALAR.$packageName"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                app.unregisterReceiver(this)
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                onDone(status == PackageInstaller.STATUS_SUCCESS)
            }
        }
        app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        // Mutable, para que Android le agregue el resultado; con el paquete
        // puesto, como pide Android 14 a las mutables.
        val result = PendingIntent.getBroadcast(
            app,
            0,
            Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        runCatching { app.packageManager.packageInstaller.uninstall(packageName, result.intentSender) }
            .onFailure {
                Log.w(TAG, "No pude desinstalar $packageName", it)
                app.unregisterReceiver(receiver)
                onDone(false)
            }
    }

    private const val TAG = "Slowtown"
}
