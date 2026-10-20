package com.slowtown.launcher.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.slowtown.launcher.Prefs

/**
 * Las apps del nivel 2, que no se abren nunca: las de [Fixed.APPS] y cualquier
 * navegador que no sea Chrome (no tienen políticas: con ellos las páginas
 * bloqueadas se abrirían igual). Android las deja pausadas: ícono gris y, si
 * se intenta abrir una, un aviso del sistema. No se borra nada: datos y
 * sesiones quedan donde estaban.
 *
 * Pausadas y no escondidas: la pausa sobrevive a que Play actualice la app,
 * y en Ajustes se ve qué pasa. Sólo Slowtown la saca (adb no puede), por eso
 * la salida por cable pasa primero por [undo].
 *
 * Lo único que borra la pausa es volver a la versión de fábrica de una app de
 * sistema («Desinstalar actualizaciones»: Android rearma el paquete de cero).
 * A las de sistema eso se les bloquea; sacar el Device Owner lo suelta solo.
 */
object Vetoed {
    private const val TAG = "Slowtown"

    /**
     * Nunca, aunque abran páginas: sin ellas el teléfono no anda. Ajustes y
     * los launchers, además, por la regla de la tablet (CLAUDE.md, regla 1).
     */
    private val NEVER = setOf(
        Chrome.PACKAGE,
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.shell",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.phone",
        "com.android.server.telecom",
        "com.samsung.android.incallui",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.providers.contacts",
        "com.sec.imsservice",
        "com.sec.ims",
        "com.vzw.apnlib",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.vending",
        "com.sec.android.app.samsungapps",
    )

    /**
     * Los navegadores: lo que abre una página cualquiera, no sólo las de su
     * dominio. También los deshabilitados (en el S10e, MetaMask), para que
     * habilitarlos de nuevo no sirva de nada.
     */
    fun browsers(context: Context): Set<String> {
        val pm = context.packageManager
        val page = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        // Sin MATCH_ALL, con un navegador predeterminado Android devuelve sólo ése.
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
        return pm.queryIntentActivities(page, flags)
            .map { it.activityInfo }
            // Una app deshabilitada entera cuenta; una app común con su pantalla
            // web deshabilitada no es un navegador.
            .filter { activity ->
                when (pm.getComponentEnabledSetting(ComponentName(activity.packageName, activity.name))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> activity.enabled
                    else -> false
                }
            }
            .mapTo(mutableSetOf()) { it.packageName }
    }

    /** Lo que tiene que quedar pausado ahora. */
    fun targets(context: Context): Set<String> {
        val installed = Fixed.APPS.filterTo(mutableSetOf()) { isInstalled(context, it) }
        return installed + browsers(context) - untouchable(context)
    }

    /**
     * Pausa lo que corresponde y devuelve lo que una versión anterior pausó y
     * ésta ya no. Hace consultas al sistema: fuera del hilo principal.
     */
    @Synchronized
    fun enforce(context: Context) {
        if (!Policies.isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = Policies.admin(context)
        val prefs = Prefs(context)
        val wanted = targets(context)
        val back = prefs.vetoed - wanted
        if (back.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, back.toTypedArray(), false)
            back.forEach { if (isInstalled(context, it)) dpm.setUninstallBlocked(admin, it, false) }
        }
        val failed = dpm.setPackagesSuspended(admin, wanted.toTypedArray(), true).toSet()
        if (failed.isNotEmpty()) Log.w(TAG, "No pude pausar: $failed")
        val paused = wanted - failed
        paused.filter { isSystem(context, it) && !dpm.isUninstallBlocked(admin, it) }
            .forEach { dpm.setUninstallBlocked(admin, it, true) }
        prefs.vetoed = paused
    }

    /**
     * Para la salida por cable: las vuelve a dejar como estaban, mirando qué
     * está pausado de verdad y no sólo lo anotado (un registro que no llegó a
     * guardarse no deja nada atrás). Sacar la pausa del administrador no toca
     * la de otras apps, como Bienestar digital. Devuelve las que se pueden
     * abrir otra vez.
     */
    @Synchronized
    fun undo(context: Context): List<String> {
        if (!Policies.isDeviceOwner(context)) return emptyList()
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = Policies.admin(context)
        val prefs = Prefs(context)
        val installed = context.packageManager
            .getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.packageName }
        val paused = installed.filter { runCatching { dpm.isPackageSuspended(admin, it) }.getOrDefault(false) }
            .toSet() + prefs.vetoed.filter { it in installed }
        if (paused.isNotEmpty()) dpm.setPackagesSuspended(admin, paused.toTypedArray(), false)
        (paused + prefs.vetoed).filter { it in installed }
            .forEach { runCatching { dpm.setUninstallBlocked(admin, it, false) } }
        prefs.vetoed = emptySet()
        return paused.filterNot { runCatching { dpm.isPackageSuspended(admin, it) }.getOrDefault(false) }.sorted()
    }

    private fun untouchable(context: Context): Set<String> = buildSet {
        addAll(NEVER)
        add(context.packageName)
        addAll(Essentials.packages(context))
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .mapTo(this) { it.activityInfo.packageName }
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .mapTo(this) { it.packageName }
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
    }.isSuccess

    private fun isSystem(context: Context, packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)
}
