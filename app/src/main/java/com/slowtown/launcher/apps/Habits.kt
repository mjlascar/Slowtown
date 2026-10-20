package com.slowtown.launcher.apps

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Lo que Marcos quiere sostener: meditar 2 veces por semana y hacer ejercicio
 * 3. Sin app de hábitos aparte: se marca desde Inicio.
 */
enum class Habit(val key: String, val label: String, val perWeek: Int) {
    MEDITATE("meditar", "Meditar", 2),
    EXERCISE("ejercicio", "Ejercicio", 3),
}

object Habits {

    /** Los últimos [count] días, del más viejo a hoy: hoy queda a la derecha, como en GitHub. */
    fun lastDays(today: LocalDate, count: Int = 7): List<LocalDate> =
        (count - 1 downTo 0).map { today.minusDays(it.toLong()) }

    /** Cuántos de los últimos 7 días están marcados: la meta se mira en una ventana que corre. */
    fun lastWeekCount(days: Set<LocalDate>, today: LocalDate): Int = lastDays(today).count { it in days }

    /** El lunes de la semana de [date]. */
    fun weekStart(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun weekCount(days: Set<LocalDate>, monday: LocalDate): Int = (0L..6L).count { monday.plusDays(it) in days }

    /**
     * Semanas seguidas (de lunes a domingo) con la meta cumplida. La de ahora
     * cuenta si ya está cumplida; si no, todavía hay tiempo, y la racha se
     * cuenta desde la anterior.
     */
    fun streak(habit: Habit, days: Set<LocalDate>, today: LocalDate): Int {
        var monday = weekStart(today)
        if (weekCount(days, monday) < habit.perWeek) monday = monday.minusWeeks(1)
        var weeks = 0
        while (weekCount(days, monday) >= habit.perWeek) {
            weeks++
            monday = monday.minusWeeks(1)
        }
        return weeks
    }

    fun parse(stored: Set<String>): Set<LocalDate> =
        stored.mapNotNullTo(mutableSetOf()) { runCatching { LocalDate.parse(it) }.getOrNull() }
}
