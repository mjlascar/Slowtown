package com.slowtown.launcher

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.slowtown.launcher.apps.Folder
import com.slowtown.launcher.apps.Folders
import com.slowtown.launcher.apps.HabitKind
import com.slowtown.launcher.apps.HabitPlan
import com.slowtown.launcher.apps.Habits
import com.slowtown.launcher.ui.HomeBlocks
import java.time.DayOfWeek

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

    /**
     * Las que no se ven al recorrer la lista de todas las apps, pero sí al
     * buscarlas: las que tienen que estar instaladas y andar de fondo, como el
     * teclado, sin ocupar lugar (lo pidió Marcos). Como componentes, igual que
     * [favorites]. Sin contraseña: no aflojan nada.
     */
    var hiddenApps: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_APPS, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_APPS, value).apply()

    /**
     * El tamaño de Inicio, en por ciento (de 70 a 100): achica todo junto,
     * letras, márgenes y hábitos. Marcos lo ve grande en el S10e.
     */
    var homeScalePercent: Int
        get() = prefs.getInt(KEY_HOME_SCALE, 100).coerceIn(MIN_HOME_SCALE, 100)
        set(value) = prefs.edit().putInt(KEY_HOME_SCALE, value.coerceIn(MIN_HOME_SCALE, 100)).apply()

    /**
     * Si Inicio muestra la hora grande. De entrada sí, y entonces la de la
     * barra de arriba se esconde en Inicio ([StatusBarClock]): Marcos no la
     * quiere repetida.
     */
    var showClock: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CLOCK, value).apply()

    /**
     * El tamaño y el lugar de cada parte de Inicio (la hora, la fecha, las
     * apps…), como JSON. Lo que falta o no se entiende queda como venía.
     */
    var homeBlocks: HomeBlocks
        get() = HomeBlocks.parse(prefs.getString(KEY_HOME_BLOCKS, null))
        set(value) = prefs.edit().putString(KEY_HOME_BLOCKS, value.serialize()).apply()

    /** Si Slowtown escondió la hora de la barra ([StatusBarClock]) y tiene que devolverla. */
    val statusClockHidden: Boolean
        get() = prefs.getBoolean(KEY_STATUS_CLOCK_HIDDEN, false)

    /** Cómo estaba la lista de íconos escondidos antes de esconder la hora; null si no estaba puesta. */
    val savedStatusIcons: String?
        get() = prefs.getString(KEY_STATUS_ICONS, null)

    /** commit: si se pierde, la hora de la barra quedaría escondida en todas las apps. */
    fun saveStatusIcons(value: String?, hidden: Boolean = true) {
        prefs.edit().putBoolean(KEY_STATUS_CLOCK_HIDDEN, hidden).putString(KEY_STATUS_ICONS, value).commit()
    }

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

    /** Si la sesión en curso es sólo de Ajustes, para la información de una app ([com.slowtown.launcher.admin.AdminSession]). */
    var adminSessionInfoOnly: Boolean
        get() = prefs.getBoolean(KEY_ADMIN_SESSION_INFO_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_ADMIN_SESSION_INFO_ONLY, value).apply()

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

    /** Si la hora de dormir está prendida ([com.slowtown.launcher.admin.Bedtime]). Con contraseña. */
    var bedtimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BEDTIME_ENABLED, value).apply()

    /** Cuándo empieza la noche, en minutos del día (de entrada, las 23). */
    var bedtimeStart: Int
        get() = prefs.getInt(KEY_BEDTIME_START, 23 * 60)
        set(value) = prefs.edit().putInt(KEY_BEDTIME_START, value).apply()

    /** Cuándo termina, en minutos del día (de entrada, las 7). */
    var bedtimeEnd: Int
        get() = prefs.getInt(KEY_BEDTIME_END, 7 * 60)
        set(value) = prefs.edit().putInt(KEY_BEDTIME_END, value).apply()

    /**
     * Qué noches rige, por el día en que empiezan (la del viernes va de viernes
     * a sábado): de entrada, todas. Marcos quiere poder dejar libres el viernes
     * y el sábado.
     */
    var bedtimeDays: Set<DayOfWeek>
        get() = prefs.getStringSet(KEY_BEDTIME_DAYS, null)
            ?.mapNotNullTo(mutableSetOf()) { day -> day.toIntOrNull()?.takeIf { it in 1..7 }?.let(DayOfWeek::of) }
            ?: DayOfWeek.values().toSet()
        set(value) = prefs.edit().putStringSet(KEY_BEDTIME_DAYS, value.mapTo(mutableSetOf()) { it.value.toString() }).apply()

    /** Cuándo termina la ráfaga de 5 minutos en curso; 0 si no hay. */
    var bedtimeBurstUntil: Long
        get() = prefs.getLong(KEY_BEDTIME_BURST_UNTIL, 0L)
        // commit: es lo que decide si la noche manda, no se puede perder.
        set(value) {
            prefs.edit().putLong(KEY_BEDTIME_BURST_UNTIL, value).commit()
        }

    /** El widget de Inicio, o [AppWidgetManager.INVALID_APPWIDGET_ID] si no hay. */
    var widgetId: Int
        get() = prefs.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) = prefs.edit().putInt(KEY_WIDGET_ID, value).apply()

    private companion object {
        const val KEY_ALLOWED = "allowed"
        const val KEY_FAVORITES = "favorites"
        const val KEY_FOLDERS = "folders"
        const val KEY_HIDDEN_APPS = "hidden_apps"
        const val KEY_HOME_SCALE = "home_scale_percent"
        const val KEY_SHOW_CLOCK = "show_clock"
        const val KEY_HOME_BLOCKS = "home_blocks"
        const val KEY_STATUS_CLOCK_HIDDEN = "status_clock_hidden"
        const val KEY_STATUS_ICONS = "status_icons"
        const val MIN_HOME_SCALE = 70
        const val KEY_BLOCKED_SITES = "blocked_sites"
        const val KEY_VETOED = "vetoed"
        const val KEY_CLOSED = "closed"
        const val KEY_ADMIN_SESSION_UNTIL = "admin_session_until"
        const val KEY_ADMIN_SESSION_INFO_ONLY = "admin_session_info_only"
        const val KEY_FREE_SESSION_UNTIL = "free_session_until"
        const val KEY_FREE_SESSION_USES = "free_session_uses"
        const val KEY_WIDGET_ID = "widget_id"
        const val KEY_HABITS_WIDGET = "habits_widget"
        const val KEY_BEDTIME_ENABLED = "bedtime_enabled"
        const val KEY_BEDTIME_START = "bedtime_start"
        const val KEY_BEDTIME_END = "bedtime_end"
        const val KEY_BEDTIME_DAYS = "bedtime_days"
        const val KEY_BEDTIME_BURST_UNTIL = "bedtime_burst_until"
        const val KEY_HABIT_PREFIX = "habit_"
        const val KEY_HABIT_PLAN = "habit_plan"
    }
}
