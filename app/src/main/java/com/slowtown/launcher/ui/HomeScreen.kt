package com.slowtown.launcher.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.update.UpdateState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lo que frena ahora las apps de siempre, con su ráfaga de 5 minutos: la
 * noche o Flow State. Inicio lo dice, y la ráfaga aparece recién al tocarlo.
 */
sealed interface Pause {
    data object Night : Pause

    /** Flow State, hasta [until] («13:00»), o null si no se sabe. */
    data class Flow(val until: String?) : Pause
}

/**
 * La hora grande (si se eligió) con la fecha y el tiempo de pantalla, los
 * avisos, las apps de Inicio en texto y, abajo de todo, el widget si hay uno:
 * cada parte con el tamaño y el lugar de «Cómo se ve Inicio». Manteniendo
 * apretada la cabecera se abre la administración; deslizando para arriba,
 * todas las apps, y para abajo, las notificaciones (como en Olauncher: sin la
 * barra de arriba, que a veces se esconde, es lo más a mano).
 *
 * @param apps las apps de Inicio, o null mientras se cargan (para no mostrar
 *   el cartel de ayuda un instante en cada vuelta a Inicio).
 * @param hint qué decir si no hay ninguna.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    apps: List<LaunchableApp>?,
    hint: String,
    safeMode: Boolean,
    /** Un aviso corto abajo de la fecha, por ejemplo «Todo habilitado hasta las 17:52». */
    notice: String?,
    /**
     * De noche o en Flow State, Inicio lo dice («Es Bedtime, andá a dormir»), y
     * al tocarlo aparece la ráfaga de 5 minutos con las apps de siempre ([onBurst]).
     */
    pause: Pause?,
    onBurst: () -> Unit,
    /**
     * La actualización de Slowtown: Inicio la muestra sólo si hay una versión
     * nueva, en un renglón que se toca ([onUpdate]).
     */
    update: UpdateState,
    onUpdate: () -> Unit,
    /** El tiempo de pantalla de hoy, corto («1 h 5 min»), o null sin acceso a los datos de uso. */
    screenTime: String?,
    /** Tocar el tiempo de pantalla: abre Bienestar digital, o null si el teléfono no lo tiene. */
    onScreenTime: (() -> Unit)?,
    /** El tamaño de todo Inicio, si va la hora grande, y el tamaño y el lugar de cada parte. */
    look: HomeLook,
    /**
     * Si la barra de arriba está escondida entera (ver StatusBarClock): Inicio
     * muestra la batería junto a la fecha, como Olauncher.
     */
    showBattery: Boolean,
    /** Los paquetes de las apps de Inicio frenadas ahora (Flow State, su límite): en gris. */
    dimmed: Set<String>,
    /** El widget, que recibe hacia dónde va a lo ancho. */
    widget: (@Composable (Alignment.Horizontal) -> Unit)?,
    /**
     * Si [widget] cambia de tamaño con el resto: los hábitos sí, que son de
     * Slowtown. El de otra app no: lo dibuja su app con la densidad de
     * Android, y con la de Inicio se le avisaría un tamaño que no es.
     */
    scaleWidget: Boolean,
    onOpen: (LaunchableApp) -> Unit,
    onAdmin: () -> Unit,
    onDrawer: () -> Unit,
    onNotifications: () -> Unit,
) {
    var dragged by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    // El gesto, en medidas de verdad: el dedo no se achica con Inicio.
    val threshold = with(density) { 80.dp.toPx() }
    val battery = if (showBattery) rememberBattery().value else null
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged < -threshold) onDrawer() else if (dragged > threshold) onNotifications()
                    },
                ) { _, amount -> dragged += amount }
            }
            // El lugar de la barra de arriba queda aunque se esconda: Inicio no
            // salta cada vez que aparece o se va.
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.statusBarsIgnoringVisibility)),
    ) {
        // Todo Inicio de una vez (letras, márgenes, hábitos). La lista de todas
        // las apps y la administración no pasan por acá.
        Scaled(look.scalePercent) {
            HomeContent(
                look = look,
                screenTime = screenTime,
                onScreenTime = onScreenTime,
                battery = battery,
                notice = notice,
                pause = pause,
                onBurst = onBurst,
                update = update,
                onUpdate = onUpdate,
                safeMode = safeMode,
                apps = apps,
                dimmed = dimmed,
                hint = hint,
                widget = widget,
                scaleWidget = scaleWidget,
                realDensity = density,
                onOpen = onOpen,
                onAdmin = onAdmin,
                onDrawer = onDrawer,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Lo de adentro, al [percent] por ciento: con una densidad mayor o menor, cada
 * dp y cada sp (letras, márgenes, cuadraditos) cambia junto. Va sobre el
 * tamaño de afuera: una parte al 120 % en un Inicio al 80 % queda al 96 %.
 */
@Composable
internal fun Scaled(percent: Int, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scaled = remember(base, percent) {
        if (percent == 100) base else Density(base.density * percent / 100f, base.fontScale)
    }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * Inicio sin los gestos ni las barras del sistema. Lo usan Inicio y la vista
 * previa de «Cómo se ve Inicio»: así la muestra es Inicio y no un parecido.
 *
 * @param realDensity la de Android, para el widget de otra app (ver [HomeScreen]).
 */
@Composable
internal fun HomeContent(
    look: HomeLook,
    screenTime: String?,
    onScreenTime: (() -> Unit)?,
    /** La batería en por ciento, junto a la fecha, o null para no mostrarla. */
    battery: Int?,
    notice: String?,
    pause: Pause?,
    onBurst: () -> Unit,
    update: UpdateState,
    onUpdate: () -> Unit,
    safeMode: Boolean,
    apps: List<LaunchableApp>?,
    dimmed: Set<String>,
    hint: String,
    widget: (@Composable (Alignment.Horizontal) -> Unit)?,
    scaleWidget: Boolean,
    realDensity: Density,
    onOpen: (LaunchableApp) -> Unit,
    onAdmin: () -> Unit,
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = look.blocks
    Column(modifier.padding(horizontal = 28.dp, vertical = 32.dp)) {
        HomeHeader(look.showClock, blocks, screenTime, onScreenTime, battery, onAdmin)
        Notices(blocks[HomeBlock.NOTICES], notice, pause, onBurst, update, onUpdate, safeMode, look.showClock)
        Spacer(Modifier.height(40.dp))
        HomeApps(blocks[HomeBlock.APPS], apps, dimmed, hint, onOpen)
        Spacer(Modifier.weight(1f))
        val allApps = blocks[HomeBlock.ALL_APPS]
        if (allApps.shown) AllAppsLink(allApps, onDrawer)
        // El widget va pegado al borde de abajo (lo pidió Marcos): arriba
        // quedan la fecha y las apps, como en un teléfono de teclas.
        if (widget != null) {
            Spacer(Modifier.height(8.dp))
            val place = blocks[HomeBlock.HABITS]
            if (scaleWidget) {
                Scaled(place.percent) { widget(place.align.horizontal) }
            } else {
                CompositionLocalProvider(LocalDensity provides realDensity) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = place.align.top) { widget(place.align.horizontal) }
                }
            }
        }
    }
}

/**
 * La cabecera: la hora grande, si se eligió verla, la fecha y, sin
 * explicación, el tiempo de pantalla de hoy (lo pidió Marcos: menos texto, y
 * la cabecera más pareja); la explicación queda para quien lee la pantalla en
 * voz alta. Se mantiene apretada entera para abrir la administración, esté
 * cada cosa donde esté: sin la hora, es la fecha. Con [battery], la fecha
 * lleva la batería al lado: «Martes 27 de octubre · 54 %».
 */
@Composable
private fun HomeHeader(
    showClock: Boolean,
    blocks: HomeBlocks,
    screenTime: String?,
    onScreenTime: (() -> Unit)?,
    battery: Int?,
    onLongPress: () -> Unit,
) {
    val now by rememberMinute()
    val day = now.format(DATE).replaceFirstChar { it.titlecase(SPANISH) }
    val date = if (battery != null) "$day · $battery %" else day
    val datePlace = blocks[HomeBlock.DATE]
    val timePlace = blocks[HomeBlock.SCREEN_TIME].takeIf { screenTime != null }
    val time = @Composable {
        if (screenTime != null) ScreenTimeText(screenTime, blocks[HomeBlock.SCREEN_TIME].align, onScreenTime, onLongPress)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
    ) {
        if (showClock) {
            HeaderLine(
                lead = blocks[HomeBlock.CLOCK],
                clock = true,
                side = timePlace,
                leadContent = { Text(now.format(HOUR), style = MaterialTheme.typography.displayLarge) },
                sideContent = time,
            )
            Scaled(datePlace.percent) {
                Box(Modifier.fillMaxWidth(), contentAlignment = datePlace.align.top) { DateText(date, datePlace.align) }
            }
        } else {
            // Sin la hora, la fecha encabeza, y el tiempo de pantalla la acompaña igual.
            HeaderLine(
                lead = datePlace,
                clock = false,
                side = timePlace,
                leadContent = { DateText(date, datePlace.align) },
                sideContent = time,
            )
        }
    }
}

/**
 * La hora de ahora, al minuto, mirada una vez por segundo mientras Inicio se
 * ve, y apenas vuelve a verse o recupera el foco (al cerrar el panel de
 * notificaciones). Antes esperaba hasta el minuto siguiente con un delay, y un
 * delay no corre con el teléfono dormido: al desbloquear, Inicio mostraba unos
 * segundos la hora de cuando se apagó la pantalla, y a veces quedaba minutos
 * atrás de la del sistema (lo vio Marcos). Mirarla cada segundo es una cuenta;
 * Inicio se vuelve a dibujar sólo cuando cambia el minuto.
 */
@Composable
private fun rememberMinute(): State<LocalDateTime> {
    val minute = remember { mutableStateOf(currentMinute()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(lifecycle, focused) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                minute.value = currentMinute()
                delay(1_000L - System.currentTimeMillis() % 1_000L)
            }
        }
    }
    return minute
}

private fun currentMinute(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)

/**
 * La batería en por ciento, mientras Inicio se ve. Android avisa cada vez que
 * cambia y guarda el último aviso, así que llega apenas se pide.
 */
// RECEIVER_NOT_EXPORTED es de API 33; en Android 12 el sistema lo ignora, y
// el aviso de la batería lo manda sólo el sistema igual.
@SuppressLint("InlinedApi")
@Composable
private fun rememberBattery(): State<Int?> {
    val context = LocalContext.current
    val percent = remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                percent.value = percentOf(intent)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            ?.let { percent.value = percentOf(it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

private fun percentOf(battery: Intent): Int? {
    val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}

/**
 * El primer renglón de la cabecera: la hora (sin ella, la fecha) y el tiempo
 * de pantalla. Comparten el renglón si van en lugares distintos y entran sin
 * tocarse; si no, el tiempo de pantalla va en su renglón, justo debajo. Con la
 * fecha de un lado y el tiempo de pantalla del otro, la fecha se parte en dos
 * renglones antes que bajarlo, como fue siempre.
 *
 * @param side el lugar del tiempo de pantalla, o null si no hay.
 */
@Composable
private fun HeaderLine(
    lead: BlockLook,
    clock: Boolean,
    side: BlockLook?,
    leadContent: @Composable () -> Unit,
    sideContent: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(LEAD)) { Scaled(lead.percent, leadContent) }
            if (side != null) Box(Modifier.layoutId(SIDE)) { Scaled(side.percent, sideContent) }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val free = Constraints(maxWidth = width)
        val leadPart = measurables.first { it.layoutId == LEAD }
        val sidePart = measurables.firstOrNull { it.layoutId == SIDE }
        if (side == null || sidePart == null) {
            val l = leadPart.measure(free)
            return@Layout layout(width, constraints.constrainHeight(l.height)) {
                l.placeRelative(xOf(lead.align, l.width, width), 0)
            }
        }
        val gap = HEADER_GAP.roundToPx()
        val s = sidePart.measure(free)
        val opposite = lead.align != side.align && lead.align != BlockAlign.CENTER && side.align != BlockAlign.CENTER
        val beside = width - gap - s.width
        // Si no le quedara ni un tercio, mejor que el tiempo de pantalla baje.
        val wraps = !clock && opposite && beside >= width / 3
        val l = leadPart.measure(if (wraps) Constraints(maxWidth = beside) else free)
        var lx = xOf(lead.align, l.width, width)
        val sx = xOf(side.align, s.width, width)
        // Centrada, la hora (o la fecha) se corre un poco para que el tiempo de
        // pantalla entre a su lado: si no, según lo que dijera, bajaba un
        // renglón o no (a Marcos le quedaba debajo por un pelo, y feo).
        if (lead.align == BlockAlign.CENTER && side.align != BlockAlign.CENTER) {
            val toEnd = side.align == BlockAlign.END
            val overlap = if (toEnd) lx + l.width + gap - sx else sx + s.width + gap - lx
            val room = if (toEnd) lx else width - lx - l.width
            if (overlap in 1..minOf(MAX_NUDGE.roundToPx(), room)) lx += if (toEnd) -overlap else overlap
        }
        val apart = if (lx <= sx) lx + l.width + gap <= sx else sx + s.width + gap <= lx
        if (lead.align == side.align || !apart) {
            return@Layout layout(width, constraints.constrainHeight(l.height + s.height)) {
                l.placeRelative(lx, 0)
                s.placeRelative(sx, l.height)
            }
        }
        var ly = 0
        var sy = 0
        if (clock) {
            // A la altura de lo alto de los números de la hora, que crecen con ella.
            sy = (DIGITS_TOP.toPx() * lead.percent / 100f).roundToInt()
        } else {
            // Con la fecha, en la misma línea de base.
            val lb = l[FirstBaseline]
            val sb = s[FirstBaseline]
            if (lb != AlignmentLine.Unspecified && sb != AlignmentLine.Unspecified) {
                ly = maxOf(lb, sb) - lb
                sy = maxOf(lb, sb) - sb
            }
        }
        layout(width, constraints.constrainHeight(maxOf(ly + l.height, sy + s.height))) {
            l.placeRelative(lx, ly)
            s.placeRelative(sx, sy)
        }
    }
}

private fun xOf(align: BlockAlign, size: Int, room: Int): Int = when (align) {
    BlockAlign.START -> 0
    BlockAlign.CENTER -> (room - size) / 2
    BlockAlign.END -> room - size
}

private const val LEAD = "hora o fecha"
private const val SIDE = "tiempo de pantalla"
private val HEADER_GAP = 12.dp
private val DIGITS_TOP = 10.dp

/** Lo más que se corre la hora centrada para que el tiempo de pantalla entre a su lado. */
private val MAX_NUDGE = 24.dp

@Composable
private fun DateText(date: String, align: BlockAlign) {
    Text(
        date,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align.textAlign,
    )
}

/**
 * El tiempo de pantalla de hoy, sólo el número: la explicación, para el lector
 * de pantalla. Tocándolo se abre Bienestar digital, con el detalle por app (lo
 * pidió Marcos); mantenido apretado entra a la administración, como el resto de
 * la cabecera.
 */
@Composable
private fun ScreenTimeText(screenTime: String, align: BlockAlign, onOpen: (() -> Unit)?, onLongPress: () -> Unit) {
    val touch = if (onOpen == null) {
        Modifier
    } else {
        Modifier.combinedClickable(onClickLabel = "Ver el detalle", onLongClick = onLongPress, onClick = onOpen)
    }
    Text(
        screenTime,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleLarge,
        textAlign = align.textAlign,
        modifier = touch.semantics { contentDescription = "Hoy: $screenTime de pantalla" },
    )
}

/**
 * Los avisos, debajo de la cabecera: «Todo habilitado…» o por qué se frenó
 * una app, el de la noche o Flow State, la actualización y los cuelgues.
 */
@Composable
private fun Notices(
    place: BlockLook,
    notice: String?,
    pause: Pause?,
    onBurst: () -> Unit,
    update: UpdateState,
    onUpdate: () -> Unit,
    safeMode: Boolean,
    showClock: Boolean,
) {
    val textAlign = place.align.textAlign
    Scaled(place.percent) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = place.align.horizontal) {
            if (notice != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = textAlign,
                )
            }
            if (pause != null) {
                // La ráfaga no está siempre a la vista (lo pidió Marcos): hay que
                // tocar el aviso. Al irse de Inicio o al terminar la noche, se esconde.
                var burstShown by remember { mutableStateOf(false) }
                Spacer(Modifier.height(12.dp))
                Text(
                    pauseNotice(pause),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = textAlign,
                    modifier = Modifier
                        .clickable { burstShown = !burstShown }
                        .padding(vertical = 4.dp),
                )
                if (burstShown) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBurst) { Text("Usar 5 minutos") }
                }
            }
            // Debajo de los otros avisos: es lo menos urgente.
            if (update is UpdateState.Pending) {
                Spacer(Modifier.height(8.dp))
                UpdateLine(update, onUpdate, textAlign)
            }
            if (safeMode) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Slowtown se colgó varias veces seguidas y se pausó solo. " +
                        "Mantené apretada ${if (showClock) "la hora" else "la fecha"} para revisarlo.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = textAlign,
                )
            }
        }
    }
}

/**
 * Las apps de Inicio, en texto. Una columna común y no una lista con scroll:
 * así deslizar para arriba en cualquier lado abre todas las apps, sin
 * pelearse con el scroll. Cada renglón se toca a todo lo ancho, esté el
 * nombre donde esté. Las frenadas ([dimmed]) van en gris: tocarlas dice por qué.
 */
@Composable
private fun HomeApps(
    place: BlockLook,
    apps: List<LaunchableApp>?,
    dimmed: Set<String>,
    hint: String,
    onOpen: (LaunchableApp) -> Unit,
) {
    val textAlign = place.align.textAlign
    Scaled(place.percent) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = place.align.horizontal) {
            if (apps != null && apps.isEmpty()) {
                Text(
                    hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = textAlign,
                )
            }
            apps.orEmpty().forEach { app ->
                Text(
                    app.label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (app.packageName in dimmed) MaterialTheme.colorScheme.outline else Color.Unspecified,
                    textAlign = textAlign,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(app) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

/** «↑ todas las apps», arriba del widget, si se eligió verlo (de entrada no: se desliza para arriba). */
@Composable
private fun AllAppsLink(place: BlockLook, onDrawer: () -> Unit) {
    // A un costado se corre lo que mide el margen del toque: así la flecha
    // queda en la línea de las apps, y no 12 más adentro.
    val shift = when (place.align) {
        BlockAlign.START -> (-12).dp
        BlockAlign.CENTER -> 0.dp
        BlockAlign.END -> 12.dp
    }
    Scaled(place.percent) {
        Box(Modifier.fillMaxWidth(), contentAlignment = place.align.top) {
            Text(
                "↑ todas las apps",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .offset(x = shift)
                    .clickable(onClick = onDrawer)
                    .padding(12.dp),
            )
        }
    }
}

private val SPANISH = Locale.forLanguageTag("es-AR")

/** «Es *Bedtime*, andá a dormir» o «Es *Flow State* hasta las 13:00». */
private fun pauseNotice(pause: Pause) = buildAnnotatedString {
    append("Es ")
    when (pause) {
        Pause.Night -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Bedtime") }
            append(", andá a dormir")
        }
        is Pause.Flow -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Flow State") }
            pause.until?.let { append(" hasta las $it") }
        }
    }
}
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", SPANISH)
