package com.slowtown.launcher.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.Habit
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
    marks: Map<Habit, Set<LocalDate>>,
    today: LocalDate,
    onToggleToday: (Habit) -> Unit,
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
        Habit.entries.forEach { habit ->
            val done = marks[habit].orEmpty()
            val count = Habits.lastWeekCount(done, today)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$count/${habit.perWeek}",
                    modifier = Modifier.width(COUNT),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (count >= habit.perWeek) DONE else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .clearAndSetSemantics {
                            contentDescription = "${habit.label}, últimos 7 días: $count de ${habit.perWeek}. Ver el resumen"
                        }
                        .clickable(onClick = onOpen),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    days.forEach { day -> Cell(done = day in done, today = day == today, size = CELL) }
                }
                Spacer(Modifier.width(14.dp))
                HabitButton(habit, doneToday = today in done, onToggle = { onToggleToday(habit) })
            }
        }
    }
}

/** El botón de hoy: vacío con el dibujo, o verde cuando hoy ya está marcado. */
@Composable
private fun HabitButton(habit: Habit, doneToday: Boolean, onToggle: () -> Unit) {
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
            .semantics { contentDescription = todayLabel(habit) },
        contentAlignment = Alignment.Center,
    ) {
        HabitIcon(
            habit,
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
    marks: Map<Habit, Set<LocalDate>>,
    today: LocalDate,
    onToggle: (Habit, LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(title = "Hábitos", onBack = onBack) {
        HintText("Tocá un día para marcarlo o desmarcarlo.")
        Habit.entries.forEach { habit ->
            val done = marks[habit].orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HabitIcon(
                    habit,
                    color = MaterialTheme.colorScheme.onSurface,
                    background = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(22.dp),
                )
                Text(habit.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${habit.perWeek} por semana",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val week = Habits.weekCount(done, Habits.weekStart(today))
            HintText(
                "Esta semana: $week de ${habit.perWeek} · Semanas cumplidas seguidas: " +
                    "${Habits.streak(habit, done, today)} · Días en total: ${done.count { !it.isAfter(today) }}",
            )
            Graph(habit, done, today, onToggle)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun Graph(habit: Habit, done: Set<LocalDate>, today: LocalDate, onToggle: (Habit, LocalDate) -> Unit) {
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
                        if (w == 0 || monday.month != monday.minusWeeks(1).month) {
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
                                                "${habit.label}, ${date.format(DAY)}: ${if (marked) "sí" else "no"}"
                                        }
                                        .clickable { onToggle(habit, date) },
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

/** El dibujo de cada hábito, a mano para que vaya con el resto: blanco y negro, sin emojis. */
@Composable
fun HabitIcon(habit: Habit, color: Color, background: Color, modifier: Modifier = Modifier) {
    when (habit) {
        Habit.MEDITATE -> YinYang(color, background, modifier)
        Habit.EXERCISE -> Dumbbell(color, modifier)
    }
}

@Composable
private fun YinYang(color: Color, background: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val r = size.minDimension / 2 - stroke / 2
        val c = center
        // La mitad clara a la derecha, y las dos cabezas: clara arriba, oscura
        // abajo, cada una con un punto del color contrario.
        drawArc(color, -90f, 180f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        drawCircle(color, r / 2, Offset(c.x, c.y - r / 2))
        drawCircle(background, r / 2, Offset(c.x, c.y + r / 2))
        drawCircle(background, r / 7, Offset(c.x, c.y - r / 2))
        drawCircle(color, r / 7, Offset(c.x, c.y + r / 2))
        drawCircle(color, r, c, style = Stroke(stroke))
    }
}

@Composable
private fun Dumbbell(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.04f)
        // La barra, los discos grandes y los chicos de cada lado.
        drawRoundRect(color, Offset(w * 0.2f, h * 0.45f), Size(w * 0.6f, h * 0.1f), corner)
        drawRoundRect(color, Offset(w * 0.17f, h * 0.2f), Size(w * 0.12f, h * 0.6f), corner)
        drawRoundRect(color, Offset(w * 0.71f, h * 0.2f), Size(w * 0.12f, h * 0.6f), corner)
        drawRoundRect(color, Offset(w * 0.04f, h * 0.31f), Size(w * 0.1f, h * 0.38f), corner)
        drawRoundRect(color, Offset(w * 0.86f, h * 0.31f), Size(w * 0.1f, h * 0.38f), corner)
    }
}

/** Para la pantalla y para las pruebas: lo que dice el botón de hoy de cada hábito. */
fun todayLabel(habit: Habit): String = when (habit) {
    Habit.MEDITATE -> "Meditación de hoy"
    Habit.EXERCISE -> "Ejercicio de hoy"
}

// L M X J V S D, como en los calendarios en castellano.
private fun initial(day: LocalDate): String = "LMXJVSD"[day.dayOfWeek.value - 1].toString()

private val SPANISH = Locale.forLanguageTag("es-AR")
private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
