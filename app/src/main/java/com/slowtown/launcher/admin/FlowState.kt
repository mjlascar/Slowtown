package com.slowtown.launcher.admin

import android.content.Context
import com.slowtown.launcher.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Un horario de Flow State: de [start] a [end], en minutos del día (puede
 * cruzar la medianoche), los [days] en que empieza.
 */
data class FlowWindow(val start: Int, val end: Int, val days: Set<DayOfWeek>)

/**
 * Flow State: en sus horarios, con Slowtown activo, las apps elegidas no se
 * abren (lo pidió Marcos, «así como está Bedtime», para concentrarse). Puede
 * tener varios horarios, cada uno con sus días; las apps son las mismas en
 * todos. Para usar una igual, la ráfaga de 5 minutos de Bedtime, sin
 * contraseña desde Inicio ([Bedtime.startBurst]): suelta las dos.
 *
 * Lo hace cumplir lo mismo que el resto ([Rules]): la lista de lock task con
 * Device Owner y, sin cable, la guardia. Los bordes de los horarios los agenda
 * [Bedtime.schedule], junto con los de la noche.
 */
object FlowState {

    /** De entrada, de lunes a viernes de 9 a 13. */
    val DEFAULT_WINDOWS = listOf(FlowWindow(9 * 60, 13 * 60, DayOfWeek.entries.take(5).toSet()))

    /** Si ahora es Flow State: prendido, con apps elegidas y adentro de un horario (haya ráfaga o no). */
    fun isOn(context: Context, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val prefs = Prefs(context)
        return prefs.flowEnabled && prefs.flowApps.isNotEmpty() && activeWindow(prefs.flowWindows, now) != null
    }

    /** Lo que Flow State frena ahora, como paquetes: nada fuera de sus horarios o durante una ráfaga. */
    fun held(context: Context): Set<String> =
        if (isOn(context) && Bedtime.burstUntil(context) == 0L) Prefs(context).flowApps else emptySet()

    /** El horario que corre en [now], o null. Cada uno es del día en que empieza, como las noches de Bedtime. */
    fun activeWindow(windows: List<FlowWindow>, now: LocalDateTime): FlowWindow? {
        val minute = now.hour * 60 + now.minute
        return windows.firstOrNull { Bedtime.inWindow(minute, it.start, it.end) && dayOf(now, it) in it.days }
    }

    /**
     * El día en que empezó [window], que corre en [now]: el de ayer si cruzó la
     * medianoche. No es [Bedtime.nightOf], que da a la noche anterior lo que
     * empieza de madrugada: un horario de 9 a 13 es del día.
     */
    private fun dayOf(now: LocalDateTime, window: FlowWindow): DayOfWeek {
        val minute = now.hour * 60 + now.minute
        val fromYesterday = window.start > window.end && minute < window.end
        return (if (fromYesterday) now.toLocalDate().minusDays(1) else now.toLocalDate()).dayOfWeek
    }

    /**
     * Cuándo termina el Flow State que corre, para el aviso de Inicio: el final
     * de su horario, o el del que sigue si empieza justo ahí (de 9 a 13 y de 13
     * a 18 es «hasta las 18»). Null fuera de Flow State.
     */
    fun endsAt(context: Context, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val windows = Prefs(context).flowWindows
        var at = now
        repeat(windows.size) {
            val window = activeWindow(windows, at) ?: return at.takeIf { it != now }
            at = endOf(window, at)
        }
        return at.takeIf { it != now }
    }

    /** El próximo final de [window] después de [at]. */
    private fun endOf(window: FlowWindow, at: LocalDateTime): LocalDateTime {
        val day = at.toLocalDate().atStartOfDay()
        val today = day.plusMinutes(window.end.toLong())
        return if (today.isAfter(at)) today else today.plusDays(1)
    }

    /** El primer momento después de [now] en que empieza o termina un horario. */
    fun nextBoundary(windows: List<FlowWindow>, now: LocalDateTime): LocalDateTime? =
        windows.filter { it.start != it.end }.minOfOrNull { Bedtime.nextBoundary(now, it.start, it.end) }

    /** Como JSON: `[{"start":540,"end":780,"days":[1,2,3,4,5]}, …]`, con los días de 1 (lunes) a 7. */
    fun serializeWindows(windows: List<FlowWindow>): String = JSONArray().apply {
        windows.forEach { window ->
            put(
                JSONObject()
                    .put("start", window.start)
                    .put("end", window.end)
                    .put("days", JSONArray(window.days.map { it.value }.sorted())),
            )
        }
    }.toString()

    /** Sin nada guardado, los de entrada; lo que no se entiende se saltea. */
    fun parseWindows(json: String?): List<FlowWindow> {
        val stored = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return DEFAULT_WINDOWS
        return (0 until stored.length()).mapNotNull { i ->
            val item = stored.optJSONObject(i) ?: return@mapNotNull null
            val start = item.optInt("start", -1)
            val end = item.optInt("end", -1)
            if (start !in 0 until DAY || end !in 0 until DAY) return@mapNotNull null
            val days = item.optJSONArray("days") ?: JSONArray()
            FlowWindow(
                start,
                end,
                (0 until days.length()).mapNotNullTo(mutableSetOf()) { d ->
                    days.optInt(d).takeIf { it in 1..7 }?.let(DayOfWeek::of)
                },
            )
        }
    }

    private const val DAY = 24 * 60
}
