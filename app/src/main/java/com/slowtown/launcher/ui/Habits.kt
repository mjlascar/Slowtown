package com.slowtown.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.HabitKind
import com.slowtown.launcher.apps.HabitPlan
import com.slowtown.launcher.apps.Habits
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Los colores de los cuadraditos de GitHub en modo oscuro.
private val DONE = Color(0xFF39D353)
private val EMPTY = Color(0xFF161B22)
private val OUTLINE = Color(0xFF3D444D)

private val CELL = 24.dp
private val GAP = 5.dp
private val COUNT = 34.dp

/**
 * Los hábitos en Inicio: una fila por hábito con los últimos 7 días (hoy a la
 * derecha, como en GitHub), cuánto lleva contra la meta, y a la derecha de
 * todo el botón que marca hoy, pegado al cuadradito que prende. Tocar los
 * cuadraditos abre el resumen.
 */
@Composable
fun HabitsWidget(
    plan: List<HabitPlan>,
    marks: Map<HabitKind, Set<LocalDate>>,
    today: LocalDate,
    onToggleToday: (HabitKind) -> Unit,
    onOpen: () -> Unit,
) {
    val days = Habits.lastDays(today)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(COUNT))
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                days.forEach { day ->
                    Text(
                        initial(day),
                        modifier = Modifier.width(CELL),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        plan.forEach { (kind, perWeek) ->
            val done = marks[kind].orEmpty()
            val count = Habits.lastWeekCount(done, today)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$count/$perWeek",
                    modifier = Modifier.width(COUNT),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (count >= perWeek) DONE else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .clearAndSetSemantics {
                            contentDescription = "${kind.label}, últimos 7 días: $count de $perWeek. Ver el resumen"
                        }
                        .clickable(onClick = onOpen),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    days.forEach { day -> Cell(done = day in done, today = day == today, size = CELL) }
                }
                Spacer(Modifier.width(14.dp))
                HabitButton(kind, doneToday = today in done, onToggle = { onToggleToday(kind) })
            }
        }
    }
}

/** El botón de hoy: vacío con el dibujo, o verde cuando hoy ya está marcado. */
@Composable
private fun HabitButton(kind: HabitKind, doneToday: Boolean, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val background = if (doneToday) DONE else MaterialTheme.colorScheme.background
    Box(
        Modifier
            .size(40.dp)
            .background(background, CircleShape)
            .border(1.dp, if (doneToday) DONE else OUTLINE, CircleShape)
            .toggleable(value = doneToday, role = Role.Checkbox) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
            .semantics { contentDescription = kind.todayLabel },
        contentAlignment = Alignment.Center,
    ) {
        HabitGlyph(
            kind,
            color = if (doneToday) Color.Black else MaterialTheme.colorScheme.onSurface,
            background = background,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * El resumen, como el de GitHub: una columna por semana, de lunes a domingo,
 * desde la primera marca (hasta un año). Tocar un día lo marca o lo desmarca,
 * para el que se olvidó de marcarlo en Inicio.
 */
@Composable
fun HabitsScreen(
    plan: List<HabitPlan>,
    marks: Map<HabitKind, Set<LocalDate>>,
    today: LocalDate,
    onToggle: (HabitKind, LocalDate) -> Unit,
    onChoose: () -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(title = "Hábitos", onBack = onBack) {
        HintText("Tocá un día para marcarlo o desmarcarlo.")
        OutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text("Elegir hábitos") }
        plan.forEach { (kind, perWeek) ->
            val done = marks[kind].orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HabitGlyph(
                    kind,
                    color = MaterialTheme.colorScheme.onSurface,
                    background = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(22.dp),
                )
                Text(kind.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$perWeek por semana",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val week = Habits.weekCount(done, Habits.weekStart(today))
            HintText(
                "Esta semana: $week de $perWeek · Semanas cumplidas seguidas: " +
                    "${Habits.streak(perWeek, done, today)} · Días en total: ${done.count { !it.isAfter(today) }}",
            )
            Graph(kind, done, today, onToggle)
            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * Qué hábitos van en Inicio (hasta [Habits.MAX_ON_HOME]) y cuántas veces por
 * semana cada uno. Sin contraseña, como marcarlos: no afloja nada. Sacar uno
 * no borra sus días.
 */
@Composable
fun HabitsPickerScreen(
    plan: List<HabitPlan>,
    onChange: (List<HabitPlan>) -> Unit,
    onBack: () -> Unit,
) {
    var message by remember { mutableStateOf<String?>(null) }

    fun toggle(kind: HabitKind) {
        val chosen = plan.any { it.kind == kind }
        message = when {
            chosen && plan.size == 1 -> "Tiene que quedar al menos uno."
            !chosen && plan.size >= Habits.MAX_ON_HOME ->
                "Ya hay ${Habits.MAX_ON_HOME} en Inicio: sacá uno para sumar otro."
            else -> null
        }
        if (message != null) return
        onChange(if (chosen) plan.filterNot { it.kind == kind } else plan + HabitPlan(kind, kind.defaultPerWeek))
    }

    fun step(kind: HabitKind, delta: Int) {
        onChange(plan.map { if (it.kind == kind) it.copy(perWeek = (it.perWeek + delta).coerceIn(1, 7)) else it })
    }

    FormScaffold(title = "Elegir hábitos", onBack = onBack) {
        HintText(
            "Hasta ${Habits.MAX_ON_HOME} en Inicio. Tocá uno para sumarlo o sacarlo, y con − y + " +
                "elegí cuántas veces por semana.",
        )
        message?.let { ErrorText(it) }
        HabitKind.entries.forEach { kind ->
            val chosen = plan.firstOrNull { it.kind == kind }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = chosen != null, role = Role.Checkbox) { toggle(kind) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cell(done = chosen != null, today = false, size = 20.dp)
                HabitGlyph(
                    kind,
                    color = MaterialTheme.colorScheme.onSurface,
                    background = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    kind.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (chosen != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            if (chosen != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { step(kind, -1) },
                        enabled = chosen.perWeek > 1,
                        modifier = Modifier.semantics { contentDescription = "Menos veces: ${kind.label}" },
                    ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("${chosen.perWeek} por semana", style = MaterialTheme.typography.bodyLarge)
                    TextButton(
                        onClick = { step(kind, +1) },
                        enabled = chosen.perWeek < 7,
                        modifier = Modifier.semantics { contentDescription = "Más veces: ${kind.label}" },
                    ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

@Composable
private fun Graph(kind: HabitKind, done: Set<LocalDate>, today: LocalDate, onToggle: (HabitKind, LocalDate) -> Unit) {
    val cell = 15.dp
    val gap = 3.dp
    val labels = 16.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = ((maxWidth - labels) / (cell + gap)).toInt().coerceAtLeast(4)
        val lastMonday = Habits.weekStart(today)
        val first = done.filter { !it.isAfter(today) }.minOrNull()
        val since = first?.let { ChronoUnit.WEEKS.between(Habits.weekStart(it), lastMonday).toInt() + 1 } ?: 0
        val weeks = maxOf(fit, since).coerceAtMost(53)
        val firstMonday = lastMonday.minusWeeks((weeks - 1).toLong())
        val scroll = rememberScrollState()
        // Arranca mostrando lo último, como GitHub.
        LaunchedEffect(weeks) { scroll.scrollTo(scroll.maxValue) }
        Row {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Spacer(Modifier.size(labels, 14.dp))
                for (day in 0..6) {
                    Text(
                        // Como GitHub, sólo algunos días: lunes, miércoles y viernes.
                        if (day % 2 == 0 && day < 6) "LMXJVSD"[day].toString() else "",
                        modifier = Modifier.size(labels, cell),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.horizontalScroll(scroll), verticalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.size(width = (cell + gap) * weeks, height = 14.dp)) {
                    for (w in 0 until weeks) {
                        val monday = firstMonday.plusWeeks(w.toLong())
                        // Cada mes donde empieza. El de la primera columna, sólo si
                        // le queda lugar antes del siguiente (si no, se pisan).
                        val starts = if (w == 0) {
                            firstMonday.plusWeeks(2).month == firstMonday.month
                        } else {
                            monday.month != monday.minusWeeks(1).month
                        }
                        if (starts) {
                            Text(
                                monday.month.getDisplayName(TextStyle.SHORT, SPANISH).trimEnd('.'),
                                modifier = Modifier.offset(x = (cell + gap) * w),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                for (day in 0..6) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (w in 0 until weeks) {
                            val date = firstMonday.plusWeeks(w.toLong()).plusDays(day.toLong())
                            if (date.isAfter(today)) {
                                Spacer(Modifier.size(cell))
                            } else {
                                val marked = date in done
                                Cell(
                                    done = marked,
                                    today = date == today,
                                    size = cell,
                                    modifier = Modifier
                                        .semantics {
                                            contentDescription =
                                                "${kind.label}, ${date.format(DAY)}: ${if (marked) "sí" else "no"}"
                                        }
                                        .clickable { onToggle(kind, date) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(done: Boolean, today: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size / 6)
    Box(
        modifier
            .size(size)
            .background(if (done) DONE else EMPTY, shape)
            .border(if (today) 1.5.dp else 0.5.dp, if (today) MaterialTheme.colorScheme.onSurface else OUTLINE, shape),
    )
}

// L M X J V S D, como en los calendarios en castellano.
private fun initial(day: LocalDate): String = "LMXJVSD"[day.dayOfWeek.value - 1].toString()

private val SPANISH = Locale.forLanguageTag("es-AR")
private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
