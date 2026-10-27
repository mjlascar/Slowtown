package com.slowtown.launcher.admin

import android.content.Context
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.apps.ScreenTime
import org.json.JSONObject
import java.time.LocalDate

/**
 * Los límites por app (lo pidió Marcos: «máximo 1 hora de Instagram»): cuando
 * el tiempo de pantalla de hoy de una app llega a su límite, con Slowtown
 * activo, no se abre más hasta la medianoche. Cuenta igual que el tiempo de
 * pantalla de Inicio ([ScreenTime]), con los datos de uso de Android. Sin
 * ráfagas: el límite es del día, y subirlo pide la contraseña.
 *
 * Recorrer el uso del día cuesta, así que no se mira a cada rato: lo mira la
 * guardia cuando a la app de adelante se le acaba el tiempo ([Guard]), Inicio
 * al volver y los horarios a la medianoche. Lo que llegó al límite se guarda
 * con su fecha ([Prefs.limitsReached]): la lista de lock task no depende de
 * que Slowtown siga corriendo, y al cambiar el día deja de valer sola.
 */
object Limits {
    /** De a cuánto se sube o se baja un límite, en minutos. También es el mínimo. */
    const val STEP = 15
    const val MAX = 8 * 60

    /** El de una app recién elegida. */
    const val DEFAULT = 60

    /** Las que ya llegaron hoy a su límite, como paquetes. */
    fun reached(context: Context, today: LocalDate = LocalDate.now()): Set<String> {
        val stored = Prefs(context).limitsReached
        val day = stored.substringBefore('|')
        if (day != today.toString()) return emptySet()
        return stored.substringAfter('|', "").split(',').filterTo(mutableSetOf()) { it.isNotEmpty() }
    }

    /**
     * Mira el uso de hoy y guarda las que llegaron a su límite. Devuelve ese
     * uso, por paquete, para saber cuánto le queda a cada una, o null sin
     * acceso a los datos de uso (y entonces no cambia nada). Fuera del hilo
     * principal.
     */
    @Synchronized
    fun check(context: Context): Map<String, Long>? {
        val limits = Prefs(context).appLimits
        val usage = if (limits.isEmpty()) emptyMap() else ScreenTime.byApp(context) ?: return null
        val reached = limits.filter { (pkg, minutes) -> (usage[pkg] ?: 0L) >= minutes * 60_000L }.keys
        val stored = "${LocalDate.now()}|${reached.sorted().joinToString(",")}"
        val prefs = Prefs(context)
        if (prefs.limitsReached != stored) prefs.limitsReached = stored
        return usage
    }

    /** Cuánto le queda hoy a [pkg], según [usage] (de [check]); null si no tiene límite. */
    fun remaining(context: Context, pkg: String, usage: Map<String, Long>): Long? {
        val minutes = Prefs(context).appLimits[pkg] ?: return null
        return (minutes * 60_000L - (usage[pkg] ?: 0L)).coerceAtLeast(0L)
    }

    /** «1 h», «1 h 30 min», «45 min». */
    fun format(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** Como JSON: `{"com.instagram.android":60, …}`. */
    fun serialize(limits: Map<String, Int>): String = JSONObject(limits.mapValues { it.value }).toString()

    /**
     * Lo que no se entiende se saltea, y nada pasa de [MAX]. No se redondea a
     * [STEP]: eso es de la pantalla, y la prueba del emulador pone 1 minuto
     * para no esperar un cuarto de hora.
     */
    fun parse(json: String?): Map<String, Int> {
        val stored = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return stored.keys().asSequence().mapNotNull { pkg ->
            val minutes = stored.optInt(pkg, -1).takeIf { it > 0 } ?: return@mapNotNull null
            pkg to minutes.coerceAtMost(MAX)
        }.toMap()
    }

    /** De a [STEP], entre [STEP] y [MAX]: lo que se elige en la pantalla. */
    fun round(minutes: Int): Int = ((minutes + STEP / 2) / STEP * STEP).coerceIn(STEP, MAX)
}
