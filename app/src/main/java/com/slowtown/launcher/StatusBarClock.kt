package com.slowtown.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * La hora de la barra de arriba, escondida mientras Inicio muestra la hora
 * grande: Marcos no la quiere repetida. Android no deja que una app esconda
 * sólo la hora de la barra; lo que hay es la lista de íconos escondidos de la
 * barra (`icon_blacklist`, la del «SystemUI Tuner»), que es un ajuste seguro:
 * hace falta WRITE_SECURE_SETTINGS, que da provisionar.sh por adb. Sin ese
 * permiso no hace nada.
 *
 * Al esconderla se guarda cómo estaba la lista, y al mostrarla se deja igual
 * que antes. Si «clock» ya estaba (lo puso otro), no se toca.
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

    fun setHidden(context: Context, hidden: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
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
