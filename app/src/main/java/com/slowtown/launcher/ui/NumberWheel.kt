package com.slowtown.launcher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

private val WHEEL_ROW = 48.dp
private val WHEEL_WIDTH = 72.dp

// La vuelta se hace con una lista larguísima que arranca en el medio: para
// llegar a una punta habría que girar cientos de vueltas.
private const val WHEEL_LAPS = 1_000

/**
 * Una rueda de números, como la de la hora en iOS: se gira con el dedo, frena
 * con un número en el medio y después del último viene el 00. Tocar el de
 * arriba o el de abajo lo trae al medio.
 *
 * [onValueChange] llega recién cuando la rueda queda quieta, no por cada número
 * que pasa: del otro lado se guarda y se agenda.
 */
@Composable
fun NumberWheel(
    count: Int,
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    require(count > 0) { "La rueda necesita al menos un número" }
    val selected = value.mod(count)
    // La fila en píxeles enteros y la lista de tres filas justas: con un píxel
    // de diferencia el número no queda exacto en el medio y la rueda nunca se
    // da por quieta.
    val density = LocalDensity.current
    val rowPx = with(density) { WHEEL_ROW.roundToPx() }
    val row = with(density) { rowPx.toDp() }
    // remember y no rememberSaveable: al abrir la pantalla manda el valor de afuera.
    val state = remember(count) { LazyListState(firstVisibleItemIndex = WHEEL_LAPS / 2 * count + selected) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val currentValue by rememberUpdatedState(selected)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(state, count) {
        snapshotFlow { state.wheelSettledIndex() }
            .filterNotNull()
            .collect { index ->
                val settled = index % count
                if (settled != currentValue) currentOnValueChange(settled)
            }
    }
    // Un tic por número que pasa por el medio. CLOCK_TICK y no SegmentTick de
    // Compose: ese es de Android 14 y en el S10e, con 12, no vibra.
    LaunchedEffect(state) {
        snapshotFlow { state.wheelCenterItem()?.index }
            .filterNotNull()
            .drop(1)
            .collect {
                if (state.isScrollInProgress) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
    }
    // Si el valor cambia de afuera con la rueda quieta, va hasta él por el
    // camino corto. Si está girando gana el dedo: al frenar avisa lo suyo.
    LaunchedEffect(state, selected) {
        if (state.isScrollInProgress) return@LaunchedEffect
        val now = state.wheelCenterItem()?.index ?: state.firstVisibleItemIndex
        if (now % count != selected) state.scrollToItem(nearestWheelIndex(now, selected, count))
    }

    val lineColor = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(width = WHEEL_WIDTH, height = row * 3)
            .semantics { contentDescription = "$label: ${wheelDigits(selected)}" }
            .drawBehind {
                // Las dos rayas que encierran la fila del medio.
                val stroke = 1.dp.toPx()
                val top = rowPx.toFloat()
                val bottom = 2f * rowPx
                drawLine(lineColor, Offset(0f, top), Offset(size.width, top), strokeWidth = stroke)
                drawLine(lineColor, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = stroke)
            },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            // Una fila de aire arriba y abajo: el primer número visible de la
            // lista queda en el medio, y sus vecinos se ven en el aire.
            contentPadding = PaddingValues(vertical = row),
            flingBehavior = rememberSnapFlingBehavior(state, snapPosition = SnapPosition.Center),
        ) {
            items(count * WHEEL_LAPS) { index ->
                val number = wheelDigits(index % count)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(row)
                        .clickable(interactionSource = null, indication = null) {
                            scope.launch { state.animateScrollToItem(index) }
                        }
                        .semantics { contentDescription = "$label $number" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        number,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        // Más chico y apagado cuanto más lejos del medio. Se
                        // calcula al dibujar, así girar no recompone.
                        modifier = Modifier.graphicsLayer {
                            val rows = abs(
                                index - state.firstVisibleItemIndex -
                                    state.firstVisibleItemScrollOffset / rowPx.toFloat(),
                            )
                            val scale = (1f - 0.2f * rows).coerceIn(0.7f, 1f)
                            alpha = (1f - 0.55f * rows).coerceIn(0.1f, 1f)
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
    }
}

/** El número más cerca del medio, esté girando o no. */
private fun LazyListState.wheelCenterItem(): LazyListItemInfo? =
    layoutInfo.visibleItemsInfo.minByOrNull { abs(wheelOffset(it)) }

/**
 * El número quieto en el medio, o null mientras gira. Entre que se suelta el
 * dedo y arranca el frenado, la rueda puede quedar quieta un instante entre
 * dos números: eso no cuenta.
 */
private fun LazyListState.wheelSettledIndex(): Int? {
    if (isScrollInProgress) return null
    val item = wheelCenterItem() ?: return null
    return item.index.takeIf { abs(wheelOffset(item)) <= 1 }
}

/** A cuántos píxeles está el centro de [item] del centro de la rueda. */
private fun LazyListState.wheelOffset(item: LazyListItemInfo): Int {
    val info = layoutInfo
    return item.offset + item.size / 2 - (info.viewportStartOffset + info.viewportEndOffset) / 2
}

/** El índice más cercano a [from] que muestra [value]: la rueda gira lo menos posible. */
private fun nearestWheelIndex(from: Int, value: Int, count: Int): Int {
    var delta = (value - from).mod(count)
    if (delta > count / 2) delta -= count
    return from + delta
}

private fun wheelDigits(n: Int) = n.toString().padStart(2, '0')
