package com.slowtown.launcher.apps

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Los hábitos que se pueden sostener desde Inicio, cada uno con su dibujo
 * ([com.slowtown.launcher.ui.HabitGlyph]) y las veces por semana que se
 * proponen de entrada. La clave no cambia nunca: con ella se guardan los días.
 *
 * @param todayLabel lo que dice el botón de hoy (lectores de pantalla y pruebas).
 */
enum class HabitKind(val key: String, val label: String, val todayLabel: String, val defaultPerWeek: Int) {
    MEDITATE("meditar", "Meditar", "Meditación de hoy", 2),
    EXERCISE("ejercicio", "Ejercicio", "Ejercicio de hoy", 3),
    READ("leer", "Leer", "Lectura de hoy", 5),
    WALK("caminar", "Caminar", "Caminata de hoy", 4),
    BIKE("bici", "Bici", "Bici de hoy", 2),
    SWIM("nadar", "Nadar", "Natación de hoy", 2),
    YOGA("yoga", "Yoga", "Yoga de hoy", 2),
    WATER("agua", "Tomar agua", "Agua de hoy", 7),
    SLEEP("dormir", "Dormir temprano", "Dormir temprano hoy", 5),
    WRITE("escribir", "Escribir", "Escritura de hoy", 3),
    LANGUAGES("idiomas", "Idiomas", "Idiomas de hoy", 3),
    MUSIC("instrumento", "Tocar un instrumento", "Instrumento de hoy", 3),
    EAT_WELL("comer", "Comer sano", "Comida sana de hoy", 5),
    GRATITUDE("gratitud", "Gratitud", "Gratitud de hoy", 7),
    ;

    companion object {
        fun fromKey(key: String): HabitKind? = entries.firstOrNull { it.key == key }
    }
}

/** Un hábito elegido para Inicio, con cuántas veces por semana se propone. */
data class HabitPlan(val kind: HabitKind, val perWeek: Int)

object Habits {

    /** Los que entran en Inicio sin que deje de ser Inicio. */
    const val MAX_ON_HOME = 4

    /** Lo que viene puesto: lo que Marcos se propuso al empezar. */
    val DEFAULT_PLAN = listOf(HabitPlan(HabitKind.MEDITATE, 2), HabitPlan(HabitKind.EXERCISE, 3))

    /** Los últimos [count] días, del más viejo a hoy: hoy queda a la derecha, como en GitHub. */
    fun lastDays(today: LocalDate, count: Int = 7): List<LocalDate> =
        (count - 1 downTo 0).map { today.minusDays(it.toLong()) }

    /** Cuántos de los últimos 7 días están marcados: la meta se mira en una ventana que corre. */
    fun lastWeekCount(days: Set<LocalDate>, today: LocalDate): Int = lastDays(today).count { it in days }

    /** El lunes de la semana de [date]. */
    fun weekStart(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun weekCount(days: Set<LocalDate>, monday: LocalDate): Int = (0L..6L).count { monday.plusDays(it) in days }

    /**
     * La racha de un hábito: desde qué día viene y cuántos días lleva, hoy
     * incluido. Sin racha, [since] es null y [days] 0.
     */
    data class Streak(val since: LocalDate?, val days: Int)

    /**
     * La racha, sin semanas de calendario (lo pidió Marcos el 24/11: que
     * empiece el día que empezás y termine el día que la perdés). Se pierde el
     * día en que los 7 días que terminan ahí no llegan a la meta: los días ya
     * pasados, mirando lo marcado; hoy, sólo si ni marcándolo llega. Empieza
     * el primer día marcado después de la última vez que se perdió, y los 7
     * días que empiezan ahí tienen tiempo de cumplir la meta: una racha de
     * días nueva vale desde el primer día.
     */
    fun streak(perWeek: Int, days: Set<LocalDate>, today: LocalDate): Streak {
        val first = days.filter { !it.isAfter(today) }.minOrNull() ?: return Streak(null, 0)
        fun count(end: LocalDate) = (0L..6L).count { end.minusDays(it) in days }
        // El último día en que se perdió. El de antes del primer marcado siempre
        // lo es: sus 7 días no tienen nada.
        var lost = today
        while (lost >= first) {
            val missing = if (lost == today && today !in days) 1 else 0
            if (count(lost) + missing < perWeek) break
            lost = lost.minusDays(1)
        }
        val since = days.filter { !it.isBefore(lost.minusDays(5)) && !it.isAfter(today) }.minOrNull()
            ?: return Streak(null, 0)
        return Streak(since, (today.toEpochDay() - since.toEpochDay()).toInt() + 1)
    }

    fun parse(stored: Set<String>): Set<LocalDate> =
        stored.mapNotNullTo(mutableSetOf()) { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** El plan como JSON, para las preferencias: `[{"key":"meditar","perWeek":2}, …]`. */
    fun serializePlan(plan: List<HabitPlan>): String = JSONArray().apply {
        plan.forEach { put(JSONObject().put("key", it.kind.key).put("perWeek", it.perWeek)) }
    }.toString()

    /** Lo que no se entiende (una clave vieja, un número raro) se descarta; si no queda nada, lo de entrada. */
    fun parsePlan(json: String?): List<HabitPlan> {
        if (json == null) return DEFAULT_PLAN
        val plan = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val item = array.getJSONObject(i)
                val kind = HabitKind.fromKey(item.optString("key")) ?: return@mapNotNull null
                HabitPlan(kind, item.optInt("perWeek", kind.defaultPerWeek).coerceIn(1, 7))
            }.distinctBy { it.kind }.take(MAX_ON_HOME)
        }.getOrDefault(emptyList())
        return plan.ifEmpty { DEFAULT_PLAN }
    }
}
