package com.slowtown.launcher.admin

import android.content.Context
import com.slowtown.launcher.Prefs
import java.time.LocalDate

/**
 * El tiempo por día de algunas páginas (lo pidió Marcos el 24/11): cuando se
 * usa todo el de hoy, la página queda bloqueada hasta la medianoche, como las
 * de [Pages]. Lo cuenta [PageGuard], que es el que ve la dirección de Chrome:
 * el rato que esa página está adelante con la pantalla prendida. Por eso
 * rige con el bloqueo de páginas prendido, y vale con Slowtown activo o en
 * pausa, como las páginas bloqueadas. Los minutos se eligen como los límites
 * por app ([Limits]): de a [Limits.STEP].
 *
 * Una página con tiempo cubre sus subdominios, como las bloqueadas: el tiempo
 * de m.youtube.com se descuenta del de youtube.com.
 */
object SiteTimers {
    /** El de una página recién agregada. */
    const val DEFAULT = 30

    /** La página con tiempo que cubre [host], o null si no tiene. */
    fun match(context: Context, host: String): String? {
        val limits = Prefs(context).siteLimits
        if (limits.isEmpty()) return null
        val parts = host.split('.')
        return parts.indices.asSequence().map { parts.drop(it).joinToString(".") }.firstOrNull { it in limits }
    }

    /** La página con tiempo que cubre [host], si ya usó todo el de hoy. */
    fun exhausted(context: Context, host: String): String? {
        val site = match(context, host) ?: return null
        return site.takeIf { remaining(context, it) == 0L }
    }

    /** Lo que le queda hoy a [site], en milisegundos; null si no tiene tiempo. */
    fun remaining(context: Context, site: String): Long? {
        val minutes = Prefs(context).siteLimits[site] ?: return null
        return (minutes * 60_000L - (usedToday(context)[site] ?: 0L)).coerceAtLeast(0L)
    }

    /** Lo que se usó hoy de cada página, en milisegundos. */
    fun usedToday(context: Context, today: LocalDate = LocalDate.now()): Map<String, Long> {
        val stored = Prefs(context).siteUsage
        if (stored.substringBefore('|') != today.toString()) return emptyMap()
        return stored.substringAfter('|', "").split(',').mapNotNull { entry ->
            val site = entry.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            site to (entry.substringAfter('=').toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
    }

    /** Suma [millis] a lo usado hoy de [site]. */
    @Synchronized
    fun add(context: Context, site: String, millis: Long) {
        if (millis <= 0) return
        val today = LocalDate.now()
        val used = usedToday(context, today).toMutableMap()
        used[site] = (used[site] ?: 0L) + millis
        Prefs(context).siteUsage = "$today|" + used.entries.joinToString(",") { "${it.key}=${it.value}" }
    }
}
