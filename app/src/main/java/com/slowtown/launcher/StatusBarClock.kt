package com.slowtown.launcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * La hora de la barra de arriba, escondida mientras Inicio muestra la hora
 * grande: Marcos no la quiere repetida. Android no deja que una app esconda
 * sólo la hora de la barra; lo que hay es la lista de íconos escondidos de la
 * barra (`icon_blacklist`, la del «SystemUI Tuner»), que es un ajuste seguro:
 * hace falta WRITE_SECURE_SETTINGS, que da provisionar.sh por adb.
 *
 * Sin ese permiso (instalada de una release, sin cable) se esconde la barra
 * entera mientras Inicio está al frente, como hacía Olauncher, el launcher que
 * Marcos usaba antes: deslizando desde arriba vuelve un rato, e Inicio muestra
 * la batería junto a la fecha.
 *
 * Al esconder la hora se guarda cómo estaba la lista, y al mostrarla se deja
 * igual que antes. Si «clock» ya estaba (lo puso otro), no se toca.
 */
object StatusBarClock {
    private const val TAG = "Slowtown"
    private const val SETTING = "icon_blacklist"
    private const val CLOCK = "clock"

    /**
     * Lo que esconde Android cuando la lista no está puesta (los de AOSP):
     * escribir sólo «clock» los haría aparecer.
     */
    private val ANDROID_DEFAULTS = listOf("rotate", "headset")

    /** Si se puede esconder sólo la hora: con WRITE_SECURE_SETTINGS, que se da por adb. */
    fun canHideClockOnly(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Inicio con la hora grande al frente: sin la hora de la barra, o sin la
     * barra si no se puede esconder sólo la hora. La barra es de la ventana de
     * Slowtown: las otras apps la tienen siempre.
     */
    fun apply(activity: Activity, hidden: Boolean) {
        val clockOnly = canHideClockOnly(activity)
        if (clockOnly) setHidden(activity, hidden)
        // Con el permiso, la barra se muestra siempre: si llegó después (el
        // cable del paso 3), Inicio la tenía escondida entera de antes.
        val bars = activity.window.insetsController ?: return
        if (hidden && !clockOnly) {
            bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsets.Type.statusBars())
        } else {
            bars.show(WindowInsets.Type.statusBars())
        }
    }

    /** Sólo la hora, con el ajuste seguro. Sin el permiso no hace nada. */
    fun setHidden(context: Context, hidden: Boolean) {
        if (!canHideClockOnly(context)) return
        runCatching {
            val resolver = context.contentResolver
            val prefs = Prefs(context)
            val current = Settings.Secure.getString(resolver, SETTING)
            val items = current.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (hidden && !prefs.statusClockHidden && CLOCK !in items) {
                prefs.saveStatusIcons(current)
                val base = if (current == null) ANDROID_DEFAULTS else items
                Settings.Secure.putString(resolver, SETTING, (base + CLOCK).joinToString(","))
            } else if (!hidden && prefs.statusClockHidden) {
                Settings.Secure.putString(resolver, SETTING, prefs.savedStatusIcons)
                prefs.saveStatusIcons(null, hidden = false)
            }
        }.onFailure { Log.w(TAG, "No pude cambiar la hora de la barra", it) }
    }
}
