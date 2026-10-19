package com.slowtown.launcher.security

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/**
 * La red contra el peor caso: Slowtown colgándose en loop mientras es el
 * Inicio fijo y el teléfono está cerrado. El rescate que trae Android para un
 * launcher que se cuelga no toca lo que fijó un Device Owner (borra la
 * preferencia común, no la persistente), así que sin esto el teléfono quedaría
 * trabado.
 *
 * Al arrancar el proceso, le pregunta al sistema cómo terminaron las vidas
 * anteriores de Slowtown: con [LIMIT] crashes, crashes nativos o ANR dentro de
 * [WINDOW_MS], entra en modo a prueba de fallos, que abre el teléfono y suelta
 * el Inicio fijo. El modo no se va solo: hay que salir a mano desde la
 * administración, para que un bug que vuelve cada diez minutos no cierre y
 * abra el teléfono para siempre.
 */
object CrashGuard {
    private const val LIMIT = 3
    private const val WINDOW_MS = 10 * 60_000L
    private val FAILURES = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
    )

    /** Se llama al arrancar el proceso. True si el teléfono tiene que quedar abierto. */
    fun check(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_SAFE_MODE, false)) return true
        // Lo de antes de la última salida del modo ya se revisó: no cuenta.
        val since = maxOf(System.currentTimeMillis() - WINDOW_MS, prefs.getLong(KEY_LEFT_AT, 0))
        val failures = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .count { it.reason in FAILURES && it.timestamp > since }
        if (failures < LIMIT) return false
        prefs.edit().putBoolean(KEY_SAFE_MODE, true).apply()
        return true
    }

    fun inSafeMode(context: Context): Boolean = prefs(context).getBoolean(KEY_SAFE_MODE, false)

    fun leaveSafeMode(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_SAFE_MODE, false)
            .putLong(KEY_LEFT_AT, System.currentTimeMillis())
            .apply()
    }

    // En el almacenamiento que existe antes de desbloquear el teléfono, por si
    // algún día Slowtown arranca antes del primer desbloqueo.
    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("crash_guard", Context.MODE_PRIVATE)

    private const val KEY_SAFE_MODE = "safe_mode"
    private const val KEY_LEFT_AT = "left_at"
}
