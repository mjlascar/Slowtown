package com.slowtown.launcher.ui

import android.content.ComponentName
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.HabitKind
import com.slowtown.launcher.apps.Habits
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.update.UpdateState
import org.json.JSONObject
import java.time.LocalDate

/** Cómo se ve Inicio: el tamaño de todo, en por ciento, si va la hora grande, y cada parte por separado. */
data class HomeLook(val scalePercent: Int, val showClock: Boolean, val blocks: HomeBlocks = HomeBlocks.DEFAULT)

/** Dónde va una parte de Inicio, a lo ancho. La clave se guarda: no cambia nunca. */
enum class BlockAlign(val key: String, val label: String) {
    START("izquierda", "Izquierda"),
    CENTER("centro", "Centro"),
    END("derecha", "Derecha"),
    ;

    val horizontal: Alignment.Horizontal
        get() = when (this) {
            START -> Alignment.Start
            CENTER -> Alignment.CenterHorizontally
            END -> Alignment.End
        }

    /** Lo mismo, para un Box. */
    val top: Alignment
        get() = when (this) {
            START -> Alignment.TopStart
            CENTER -> Alignment.TopCenter
            END -> Alignment.TopEnd
        }

    /** Para un texto de más de un renglón: todos los renglones del mismo lado. */
    val textAlign: TextAlign
        get() = when (this) {
            START -> TextAlign.Start
            CENTER -> TextAlign.Center
            END -> TextAlign.End
        }

    companion object {
        fun fromKey(key: String): BlockAlign? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Las partes de Inicio que se acomodan por separado, en el orden en que
 * aparecen. La clave se guarda: no cambia nunca. Lo de entrada es como era
 * Inicio antes de poder acomodarlas: todo a la izquierda, salvo el tiempo de
 * pantalla (a la derecha, en el renglón de la hora) y «todas las apps» (al
 * centro), que además viene escondido: la lista se abre deslizando para
 * arriba, y Marcos no lo quiere a la vista (27/10).
 *
 * @param hideable si se puede sacar de Inicio. La hora tiene su interruptor
 *   aparte, y la fecha no se saca: sin la hora es lo que se mantiene apretado
 *   para entrar a la administración.
 */
enum class HomeBlock(
    val key: String,
    val label: String,
    val defaultAlign: BlockAlign,
    val hideable: Boolean = false,
    val shownByDefault: Boolean = true,
) {
    CLOCK("hora", "La hora", BlockAlign.START),
    DATE("fecha", "La fecha", BlockAlign.START),
    SCREEN_TIME("pantalla", "El tiempo de pantalla", BlockAlign.END),
    NOTICES("avisos", "Los avisos", BlockAlign.START),
    APPS("apps", "Las apps", BlockAlign.START),
    ALL_APPS("todas", "Todas las apps", BlockAlign.CENTER, hideable = true, shownByDefault = false),
    HABITS("habitos", "Los hábitos", BlockAlign.START),
    ;

    val default: BlockLook get() = BlockLook(100, defaultAlign, shownByDefault)
}

/** El tamaño de una parte, en por ciento sobre el de todo Inicio, su lugar a lo ancho y si se ve. */
data class BlockLook(val percent: Int, val align: BlockAlign, val shown: Boolean = true)

/** Cada parte de Inicio con su tamaño y su lugar. Están siempre todas. */
data class HomeBlocks(private val looks: Map<HomeBlock, BlockLook>) {

    operator fun get(block: HomeBlock): BlockLook = looks[block] ?: block.default

    fun with(block: HomeBlock, look: BlockLook): HomeBlocks = HomeBlocks(looks + (block to look))

    /** Como JSON, para las preferencias: `{"hora":{"percent":100,"align":"izquierda","shown":true}, …}`. */
    fun serialize(): String = JSONObject().apply {
        HomeBlock.entries.forEach { block ->
            val look = this@HomeBlocks[block]
            put(block.key, JSONObject().put("percent", look.percent).put("align", look.align.key).put("shown", look.shown))
        }
    }.toString()

    companion object {
        const val MIN_PERCENT = 60
        const val MAX_PERCENT = 150
        const val STEP = 10

        val DEFAULT = HomeBlocks(HomeBlock.entries.associateWith { it.default })

        /** Lo que falta o no se entiende (una parte nueva, un número raro) queda como viene. */
        fun parse(json: String?): HomeBlocks {
            val stored = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return DEFAULT
            return HomeBlocks(
                HomeBlock.entries.associateWith { block ->
                    val item = stored.optJSONObject(block.key) ?: return@associateWith block.default
                    BlockLook(
                        percent = roundPercent(item.optInt("percent", 100)),
                        align = BlockAlign.fromKey(item.optString("align")) ?: block.defaultAlign,
                        // Lo guardado antes de poder esconderla (0.3.2) no lo dice: como viene.
                        shown = !block.hideable || item.optBoolean("shown", block.shownByDefault),
                    )
                },
            )
        }

        /** De a [STEP], entre [MIN_PERCENT] y [MAX_PERCENT]. */
        private fun roundPercent(percent: Int): Int =
            ((percent.coerceIn(MIN_PERCENT, MAX_PERCENT) + STEP / 2) / STEP * STEP).coerceIn(MIN_PERCENT, MAX_PERCENT)
    }
}

/** El tamaño de todo Inicio, de a diez, del mínimo que acepta [com.slowtown.launcher.Prefs.homeScalePercent] al de siempre. */
private val SIZES = listOf(70, 80, 90, 100)

/** Cuánto del alto del teléfono ocupa la vista previa: lo que queda es para los controles. */
private const val PREVIEW_HEIGHT = 0.35f

/**
 * Cómo se ve Inicio, en la administración: el tamaño de todo Inicio junto
 * (Marcos lo veía grande en el S10e), la hora grande, y el tamaño y el lugar
 * de cada parte. Arriba y fija, la vista previa: así cada cambio se ve sin
 * volver a Inicio (entrar de nuevo pediría la contraseña) y sin perderla de
 * vista al bajar por los controles.
 */
@Composable
fun HomeLookScreen(
    look: HomeLook,
    /** Si en el lugar de los hábitos hay un widget de otra app: a ése no lo cambia esta pantalla. */
    appWidget: Boolean,
    onChange: (HomeLook) -> Unit,
    onBack: () -> Unit,
) {
    // Todo el teléfono, barras incluidas: la miniatura tiene sus proporciones.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = DpSize(maxWidth, maxHeight)
        // Como FormScaffold, con la vista previa fija entre el título y lo que se desliza.
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // Las letras chicas de la administración, salvo en la vista previa, que es Inicio.
            CompactTexts { BackBar("Cómo se ve Inicio", onBack) }
            HomePreview(
                look,
                screen,
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp, bottom = 8.dp),
            )
            CompactTexts {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Tamaño", style = MaterialTheme.typography.titleMedium)
                    HintText("Achica todo junto: las letras, los márgenes y los hábitos.")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SIZES.forEach { percent ->
                            SizeChoice(percent, selected = percent == look.scalePercent, modifier = Modifier.weight(1f)) {
                                onChange(look.copy(scalePercent = percent))
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = look.showClock, role = Role.Switch) { onChange(look.copy(showClock = it)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("La hora grande", style = MaterialTheme.typography.titleMedium)
                            HintText(
                                "Con la hora grande, la de la barra de arriba se esconde en Inicio (o la " +
                                    "barra entera, si el teléfono no deja esconder sólo la hora: entonces la " +
                                    "batería pasa junto a la fecha).",
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(checked = look.showClock, onCheckedChange = null)
                    }
                    Text("Cada parte", style = MaterialTheme.typography.titleMedium)
                    HintText(
                        "El tamaño de cada una va sobre el de todo Inicio. El tiempo de pantalla comparte " +
                            "el renglón de la hora (sin ella, el de la fecha) si están en lugares distintos.",
                    )
                    HomeBlock.entries.forEach { block ->
                        BlockRow(block, look.blocks[block]) { onChange(look.copy(blocks = look.blocks.with(block, it))) }
                        if (block == HomeBlock.HABITS && appWidget) {
                            HintText("Con el widget de otra app no cambia nada: lo dibuja su app, a todo lo ancho.")
                        }
                    }
                    if (look.blocks != HomeBlocks.DEFAULT) {
                        TextButton(onClick = { onChange(look.copy(blocks = HomeBlocks.DEFAULT)) }) {
                            Text("Volver a como venía")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inicio en miniatura, con las proporciones del teléfono y las mismas piezas
 * que Inicio ([HomeContent]), con datos de muestra: tres apps, un aviso y los
 * hábitos. Se arma al tamaño del teléfono y se achica con la densidad, igual
 * que el tamaño de Inicio. No se toca, y el lector de pantalla la nombra
 * nomás.
 */
@Composable
private fun HomePreview(look: HomeLook, screen: DpSize, modifier: Modifier = Modifier) {
    val base = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Lo que Inicio deja libre para las barras del sistema, en dp del teléfono.
    val bars = WindowInsets.safeDrawing
    val margins = with(base) {
        PaddingValues.Absolute(
            left = bars.getLeft(this, direction).toDp(),
            top = bars.getTop(this).toDp(),
            right = bars.getRight(this, direction).toDp(),
            bottom = bars.getBottom(this).toDp(),
        )
    }
    val height = screen.height * PREVIEW_HEIGHT
    val width = height * (screen.width / screen.height)
    val miniature = remember(base, width, screen.width) {
        Density(base.density * (width / screen.width), base.fontScale)
    }
    val today = remember { LocalDate.now() }
    val apps = remember { SAMPLE_APPS.map { LaunchableApp(it, ComponentName(SAMPLE_PACKAGE, it)) } }
    val marks = remember(today) { sampleMarks(today) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clearAndSetSemantics { contentDescription = "Vista previa de Inicio" },
    ) {
        CompositionLocalProvider(LocalDensity provides miniature) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(margins),
            ) {
                Scaled(look.scalePercent) {
                    HomeContent(
                        look = look,
                        screenTime = "44 min",
                        onScreenTime = null,
                        battery = null,
                        notice = null,
                        // De muestra, el aviso de la noche: «Es Bedtime, andá a dormir».
                        pause = Pause.Night,
                        onBurst = {},
                        update = UpdateState.None,
                        onUpdate = {},
                        safeMode = false,
                        apps = apps,
                        dimmed = emptySet(),
                        hint = "",
                        widget = { align ->
                            HabitsWidget(
                                plan = Habits.DEFAULT_PLAN,
                                marks = marks,
                                today = today,
                                onToggleToday = {},
                                onOpen = {},
                                alignment = align,
                            )
                        },
                        scaleWidget = true,
                        realDensity = miniature,
                        onOpen = {},
                        onAdmin = {},
                        onDrawer = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Una muestra no se toca: esto se queda con los toques antes que lo de abajo.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                },
        )
    }
}

private const val SAMPLE_PACKAGE = "muestra"
private val SAMPLE_APPS = listOf("Teléfono", "WhatsApp", "Maps")

/** Unas semanas de hábitos de muestra, para que se vean los cuadraditos y los fuegos. */
private fun sampleMarks(today: LocalDate): Map<HabitKind, Set<LocalDate>> = mapOf(
    HabitKind.MEDITATE to (1L..21L step 3).map { today.minusDays(it) }.toSet(),
    HabitKind.EXERCISE to (0L..21L step 2).map { today.minusDays(it) }.toSet(),
)

/**
 * Una parte: su nombre y, abajo, el tamaño a la izquierda y el lugar a la
 * derecha. La que se puede sacar lleva al lado del nombre si se ve, y
 * escondida no muestra el resto.
 */
@Composable
private fun BlockRow(block: HomeBlock, look: BlockLook, onChange: (BlockLook) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (block.hideable) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = look.shown, role = Role.Switch) { onChange(look.copy(shown = it)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(block.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = look.shown, onCheckedChange = null)
            }
            if (!look.shown) HintText(HIDDEN_HINT.getValue(block))
        } else {
            Text(block.label, style = MaterialTheme.typography.bodyLarge)
        }
        if (look.shown) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SizeStepper(block.label, look.percent) { onChange(look.copy(percent = it)) }
                Spacer(Modifier.weight(1f))
                AlignChoice(block.label, look.align) { onChange(look.copy(align = it)) }
            }
        }
    }
}

/** Qué pasa sin una parte que se puede sacar. */
private val HIDDEN_HINT = mapOf(
    HomeBlock.ALL_APPS to "Sin el renglón, la lista se abre deslizando para arriba en Inicio.",
)

/** «−  100 %  +», de a diez. */
@Composable
private fun SizeStepper(name: String, percent: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(plus = false, enabled = percent > HomeBlocks.MIN_PERCENT, description = "Más chico: $name") {
            onChange((percent - HomeBlocks.STEP).coerceAtLeast(HomeBlocks.MIN_PERCENT))
        }
        Text(
            "$percent %",
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .widthIn(min = 52.dp)
                // «100 %» solo no dice de qué; y al cambiar, que lo diga.
                .clearAndSetSemantics {
                    contentDescription = "$name: $percent %"
                    liveRegion = LiveRegionMode.Polite
                },
        )
        StepButton(plus = true, enabled = percent < HomeBlocks.MAX_PERCENT, description = "Más grande: $name") {
            onChange((percent + HomeBlocks.STEP).coerceAtMost(HomeBlocks.MAX_PERCENT))
        }
    }
}

/** El «−» o el «+», dibujados: sin la biblioteca de íconos, y bien en el centro. También en los límites por app. */
@Composable
internal fun StepButton(plus: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = 2.dp.toPx()
            val y = size.height / 2
            drawLine(color, Offset(stroke / 2, y), Offset(size.width - stroke / 2, y), stroke, StrokeCap.Round)
            if (plus) {
                val x = size.width / 2
                drawLine(color, Offset(x, stroke / 2), Offset(x, size.height - stroke / 2), stroke, StrokeCap.Round)
            }
        }
    }
}

/** El lugar de una parte: tres botones juntos, cada uno con su alineación dibujada; lleno el elegido. */
@Composable
private fun AlignChoice(name: String, current: BlockAlign, onSelect: (BlockAlign) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .selectableGroup()
            .clip(shape)
            .border(1.dp, colors.outline, shape),
    ) {
        BlockAlign.entries.forEach { align ->
            val selected = align == current
            Box(
                Modifier
                    .size(width = 44.dp, height = 40.dp)
                    .background(if (selected) colors.onSurface else colors.background)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(align) }
                    .semantics { contentDescription = "${align.label}: $name" },
                contentAlignment = Alignment.Center,
            ) {
                AlignGlyph(
                    align,
                    color = if (selected) colors.background else colors.onSurfaceVariant,
                    modifier = Modifier.size(width = 18.dp, height = 14.dp),
                )
            }
        }
    }
}

/** Tres renglones de texto, de largos distintos, alineados como la opción. */
@Composable
private fun AlignGlyph(align: BlockAlign, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val room = size.width - stroke
        GLYPH_LINES.forEachIndexed { i, fraction ->
            val length = room * fraction
            val start = stroke / 2 + when (align) {
                BlockAlign.START -> 0f
                BlockAlign.CENTER -> (room - length) / 2
                BlockAlign.END -> room - length
            }
            val y = stroke / 2 + (size.height - stroke) * i / (GLYPH_LINES.size - 1)
            drawLine(color, Offset(start, y), Offset(start + length, y), stroke, StrokeCap.Round)
        }
    }
}

private val GLYPH_LINES = listOf(1f, 0.55f, 0.8f)

/** Un tamaño de todo Inicio: lleno el elegido, como las noches de Bedtime. */
@Composable
private fun SizeChoice(percent: Int, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (selected) colors.onSurface else colors.background)
            .border(1.dp, if (selected) colors.onSurface else colors.outline, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$percent %",
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.background else colors.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}
