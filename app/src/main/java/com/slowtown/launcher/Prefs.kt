package com.slowtown.launcher

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.slowtown.launcher.apps.Folder
import com.slowtown.launcher.apps.Folders
import com.slowtown.launcher.apps.HabitKind
import com.slowtown.launcher.apps.HabitPlan
import com.slowtown.launcher.apps.Habits

/** Lo que eligió el usuario. La contraseña vive aparte, en [com.slowtown.launcher.security.Password]. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("slowtown", Context.MODE_PRIVATE)

    /**
     * Las apps que se pueden abrir con Slowtown activo, como componentes
     * (`paquete/actividad`) y no como paquetes: un mismo paquete puede tener
     * más de un ícono (Teléfono y Contactos, por ejemplo). Se eligen en la
     * administración, detrás de la contraseña, porque aflojan las reglas.
     */
    var allowed: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ALLOWED, value).apply()

    /** Los paquetes de [allowed], que es lo que entiende la lista de lock task. */
    val allowedPackages: Set<String>
        get() = allowed.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()

    /**
     * Las que se ven en Inicio. Se eligen sin contraseña (no aflojan nada:
     * con Slowtown activo, de las permitidas se muestran sólo éstas). Hasta
     * que alguien las elige, son las permitidas, que es como funcionaba antes.
     */
    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, null)?.toSet() ?: allowed
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    /** Las carpetas de la lista de todas las apps. Sin contraseña: son sólo orden. */
    var folders: List<Folder>
        get() = Folders.parse(prefs.getString(KEY_FOLDERS, null))
        set(value) = prefs.edit().putString(KEY_FOLDERS, Folders.serialize(value)).apply()

    /**
     * Las páginas que se suman en la configuración a las que bloquea siempre
     * el nivel 2 ([com.slowtown.launcher.admin.Fixed]). Es el nivel 1:
     * se cambian con la contraseña.
     */
    var blockedSites: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_SITES, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED_SITES, value).apply()

    /**
     * Lo que pausó el nivel 2 ([com.slowtown.launcher.admin.Vetoed]): para
     * soltarlo al salir por cable, y lo que una versión nueva ya no pause.
     */
    var vetoed: Set<String>
        get() = prefs.getStringSet(KEY_VETOED, null)?.toSet() ?: emptySet()
        // commit y no apply: sin este registro, nadie podría sacar la pausa.
        set(value) {
            prefs.edit().putStringSet(KEY_VETOED, value).commit()
        }

    /** Si el usuario quiere Slowtown activo (lock task). Sólo tiene efecto siendo Device Owner. */
    var closed: Boolean
        get() = prefs.getBoolean(KEY_CLOSED, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOSED, value).apply()

    /** Cuándo termina la sesión de administración en curso; 0 si no hay. */
    var adminSessionUntil: Long
        get() = prefs.getLong(KEY_ADMIN_SESSION_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_ADMIN_SESSION_UNTIL, value).apply()

    /** Cuándo termina «Habilitar todo» en curso; 0 si no hay. */
    var freeSessionUntil: Long
        get() = prefs.getLong(KEY_FREE_SESSION_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_FREE_SESSION_UNTIL, value).apply()

    /** Cuándo se usó «Habilitar todo», separado por comas: tiene un tope cada 12 horas. */
    var freeSessionUses: String
        get() = prefs.getString(KEY_FREE_SESSION_USES, "").orEmpty()
        // commit y no apply: es lo que hace cumplir el tope, no se puede perder.
        set(value) {
            prefs.edit().putString(KEY_FREE_SESSION_USES, value).commit()
        }

    /**
     * Si Inicio muestra los hábitos de Slowtown en el lugar del widget. De
     * entrada sí, salvo que ya haya un widget de otra app.
     */
    var habitsWidget: Boolean
        get() = prefs.getBoolean(KEY_HABITS_WIDGET, widgetId == AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) = prefs.edit().putBoolean(KEY_HABITS_WIDGET, value).apply()

    /** Los días marcados de un hábito, como fechas ISO («2026-10-19»). Sin contraseña: no afloja nada. */
    fun habitDays(kind: HabitKind): Set<String> =
        prefs.getStringSet(KEY_HABIT_PREFIX + kind.key, null)?.toSet() ?: emptySet()

    fun setHabitDays(kind: HabitKind, days: Set<String>) {
        prefs.edit().putStringSet(KEY_HABIT_PREFIX + kind.key, days).apply()
    }

    /**
     * Los hábitos de Inicio y cuántas veces por semana, en orden. De entrada,
     * meditar 2 y ejercicio 3. Sacar uno no borra sus días: si vuelve, vuelven.
     */
    var habitPlan: List<HabitPlan>
        get() = Habits.parsePlan(prefs.getString(KEY_HABIT_PLAN, null))
        set(value) = prefs.edit().putString(KEY_HABIT_PLAN, Habits.serializePlan(value)).apply()

    /** El widget de Inicio, o [AppWidgetManager.INVALID_APPWIDGET_ID] si no hay. */
    var widgetId: Int
        get() = prefs.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) = prefs.edit().putInt(KEY_WIDGET_ID, value).apply()

    private companion object {
        const val KEY_ALLOWED = "allowed"
        const val KEY_FAVORITES = "favorites"
        const val KEY_FOLDERS = "folders"
        const val KEY_BLOCKED_SITES = "blocked_sites"
        const val KEY_VETOED = "vetoed"
        const val KEY_CLOSED = "closed"
        const val KEY_ADMIN_SESSION_UNTIL = "admin_session_until"
        const val KEY_FREE_SESSION_UNTIL = "free_session_until"
        const val KEY_FREE_SESSION_USES = "free_session_uses"
        const val KEY_WIDGET_ID = "widget_id"
        const val KEY_HABITS_WIDGET = "habits_widget"
        const val KEY_HABIT_PREFIX = "habit_"
        const val KEY_HABIT_PLAN = "habit_plan"
    }
}
