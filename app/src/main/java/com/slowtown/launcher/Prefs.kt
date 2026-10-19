package com.slowtown.launcher

import android.content.ComponentName
import android.content.Context

/** Lo que eligió el usuario. La contraseña vive aparte, en [com.slowtown.launcher.security.Password]. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("slowtown", Context.MODE_PRIVATE)

    /**
     * Las apps de Inicio, como componentes (`paquete/actividad`) y no como
     * paquetes: un mismo paquete puede tener más de un ícono (Teléfono y
     * Contactos, por ejemplo) y se eligen por separado.
     */
    var allowed: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ALLOWED, value).apply()

    /** Los paquetes de [allowed], que es lo que entiende la lista de lock task. */
    val allowedPackages: Set<String>
        get() = allowed.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()

    /** Si el usuario quiere Slowtown activo (lock task). Sólo tiene efecto siendo Device Owner. */
    var closed: Boolean
        get() = prefs.getBoolean(KEY_CLOSED, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOSED, value).apply()

    /** Cuándo termina la sesión de administración en curso; 0 si no hay. */
    var adminSessionUntil: Long
        get() = prefs.getLong(KEY_ADMIN_SESSION_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_ADMIN_SESSION_UNTIL, value).apply()

    private companion object {
        const val KEY_ALLOWED = "allowed"
        const val KEY_CLOSED = "closed"
        const val KEY_ADMIN_SESSION_UNTIL = "admin_session_until"
    }
}
