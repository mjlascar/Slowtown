package com.slowtown.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.slowtown.launcher.apps.HabitKind

/** El dibujo de cada hábito, a mano para que vaya con el resto: blanco y negro, sin emojis. */
@Composable
fun HabitGlyph(kind: HabitKind, color: Color, background: Color, modifier: Modifier = Modifier) {
    // Lo que corta una figura se pinta con el fondo, que llega de afuera
    // porque el botón se pone verde cuando hoy ya está marcado.
    when (kind) {
        HabitKind.MEDITATE -> YinYang(color, background, modifier)
        HabitKind.EXERCISE -> Dumbbell(color, modifier)
        HabitKind.READ -> Book(color, modifier)
        HabitKind.WALK -> Footprints(color, modifier)
        HabitKind.BIKE -> Bicycle(color, modifier)
        HabitKind.SWIM -> Waves(color, modifier)
        HabitKind.YOGA -> Lotus(color, background, modifier)
        HabitKind.WATER -> Drop(color, background, modifier)
        HabitKind.SLEEP -> Moon(color, background, modifier)
        HabitKind.WRITE -> Pencil(color, modifier)
        HabitKind.LANGUAGES -> SpeechBubble(color, modifier)
        HabitKind.MUSIC -> Note(color, modifier)
        HabitKind.EAT_WELL -> Apple(color, modifier)
        HabitKind.GRATITUDE -> Heart(color, modifier)
    }
}

// Un solo grosor para los dibujos de línea, para que parezcan de la misma mano.
private val LINE = 1.6.dp

private fun DrawScope.outline() = Stroke(LINE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

// Las medidas van en fracciones de un cuadrado de lado 1 centrado en el lienzo:
// los mismos números sirven a 22 y a 24 dp, y nada se estira si no es cuadrado.
private fun DrawScope.at(x: Float, y: Float): Offset {
    val side = size.minDimension
    return Offset(center.x + (x - 0.5f) * side, center.y + (y - 0.5f) * side)
}

private fun DrawScope.len(fraction: Float) = fraction * size.minDimension

/**
 * Un camino en esas medidas. Con [flip] sale espejado, para escribir una sola vez lo
 * simétrico; los rellenos van enteros, porque partidos al medio dejan una raya.
 */
private class Pen(private val scope: DrawScope, private val flip: Boolean) {
    val path = Path()

    private fun pt(x: Float, y: Float) = scope.at(if (flip) 1f - x else x, y)

    fun move(x: Float, y: Float) = pt(x, y).let { path.moveTo(it.x, it.y) }
    fun line(x: Float, y: Float) = pt(x, y).let { path.lineTo(it.x, it.y) }
    fun close() = path.close()
    fun dot(x: Float, y: Float, radius: Float) = path.addOval(Rect(pt(x, y), scope.len(radius)))

    /** Una línea quebrada que arranca en el primer punto; van de a pares: x, y. */
    fun poly(vararg xy: Float) {
        move(xy[0], xy[1])
        for (i in 2 until xy.size step 2) line(xy[i], xy[i + 1])
    }

    /** Hasta ([x], [y]) tirando hacia ([cx], [cy]): con ese punto en el vértice, redondea una esquina. */
    fun bend(cx: Float, cy: Float, x: Float, y: Float) {
        val c = pt(cx, cy)
        val end = pt(x, y)
        path.quadraticTo(c.x, c.y, end.x, end.y)
    }

    fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
        val a = pt(x1, y1)
        val b = pt(x2, y2)
        val end = pt(x, y)
        path.cubicTo(a.x, a.y, b.x, b.y, end.x, end.y)
    }
}

private fun DrawScope.pen(flip: Boolean = false, draw: Pen.() -> Unit) = Pen(this, flip).apply(draw).path

private fun DrawScope.fillPath(color: Color, flip: Boolean = false, draw: Pen.() -> Unit) =
    drawPath(pen(flip, draw), color)

private fun DrawScope.strokePath(color: Color, flip: Boolean = false, draw: Pen.() -> Unit) =
    drawPath(pen(flip, draw), color, style = outline())

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

@Composable
private fun Book(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        // Las páginas bajan hacia el lomo: así se lee abierto aunque sea chico.
        for (flip in listOf(false, true)) strokePath(color, flip) {
            poly(0.38f, 0.74f, 0.08f, 0.74f, 0.08f, 0.18f, 0.32f, 0.18f)
            curve(0.419f, 0.18f, 0.5f, 0.261f, 0.5f, 0.36f)
            line(0.5f, 0.86f)
            curve(0.5f, 0.794f, 0.446f, 0.74f, 0.38f, 0.74f)
            close()
        }
    }
}

@Composable
private fun Footprints(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        // El derecho es el izquierdo espejado y un paso adelante. Con la planta ovalada
        // parecían huellas de perro: es más ancha adelante y el dedo gordo va adentro.
        for (right in listOf(false, true)) {
            translate(top = if (right) -len(0.16f) else 0f) {
                fillPath(color, flip = right) {
                    move(0.31f, 0.437f)
                    curve(0.39f, 0.437f, 0.435f, 0.503f, 0.431f, 0.58f)
                    curve(0.426f, 0.679f, 0.398f, 0.855f, 0.31f, 0.855f)
                    curve(0.222f, 0.855f, 0.194f, 0.679f, 0.189f, 0.58f)
                    curve(0.185f, 0.503f, 0.23f, 0.437f, 0.31f, 0.437f)
                    close()
                    dot(0.378f, 0.342f, 0.057f)
                    dot(0.241f, 0.353f, 0.042f)
                    dot(0.149f, 0.43f, 0.034f)
                }
            }
        }
    }
}

@Composable
private fun Bicycle(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        drawCircle(color, len(0.17f), at(0.25f, 0.6f), style = outline())
        drawCircle(color, len(0.17f), at(0.75f, 0.6f), style = outline())
        strokePath(color) {
            // Los dos triángulos del cuadro y la horquilla; después, el asiento y el manubrio.
            poly(0.25f, 0.6f, 0.46f, 0.6f, 0.39f, 0.36f)
            close()
            poly(0.39f, 0.36f, 0.67f, 0.36f, 0.75f, 0.6f)
            poly(0.67f, 0.36f, 0.46f, 0.6f)
            poly(0.39f, 0.36f, 0.365f, 0.275f)
            poly(0.31f, 0.27f, 0.42f, 0.27f)
            poly(0.67f, 0.36f, 0.64f, 0.26f, 0.58f, 0.26f)
        }
    }
}

@Composable
private fun Waves(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        // Cada media onda lleva los dos controles a 4/3 de la altura: así llega justo
        // a 0.065 y la unión con la siguiente no hace pico.
        strokePath(color) {
            for (y in listOf(0.29f, 0.5f, 0.71f)) {
                move(0.09f, y)
                for (i in 0 until 4) {
                    val x = 0.09f + i * 0.205f
                    val lift = if (i % 2 == 0) -0.087f else 0.087f
                    curve(x + 0.068f, y + lift, x + 0.137f, y + lift, x + 0.205f, y)
                }
            }
        }
    }
}

@Composable
private fun Lotus(color: Color, background: Color, modifier: Modifier) {
    Canvas(modifier) {
        // Un loto y no una hoja, que se confundiría con la de la manzana. Los pétalos de
        // los costados van enteros y el del medio los tapa con el fondo: es más simple
        // que cortar cada curva donde se cruzan.
        for (flip in listOf(false, true)) strokePath(color, flip) {
            move(0.5f, 0.82f)
            curve(0.28f, 0.82f, 0.12f, 0.66f, 0.08f, 0.42f)
            curve(0.28f, 0.44f, 0.46f, 0.6f, 0.5f, 0.82f)
            close()
        }
        val middle = pen {
            move(0.5f, 0.82f)
            curve(0.32f, 0.68f, 0.34f, 0.34f, 0.5f, 0.15f)
            curve(0.66f, 0.34f, 0.68f, 0.68f, 0.5f, 0.82f)
            close()
        }
        drawPath(middle, background)
        drawPath(middle, color, style = outline())
    }
}

@Composable
private fun Drop(color: Color, background: Color, modifier: Modifier) {
    Canvas(modifier) {
        fillPath(color) {
            move(0.5f, 0.08f)
            curve(0.58f, 0.22f, 0.8f, 0.42f, 0.8f, 0.62f)
            curve(0.8f, 0.786f, 0.666f, 0.92f, 0.5f, 0.92f)
            curve(0.334f, 0.92f, 0.2f, 0.786f, 0.2f, 0.62f)
            curve(0.2f, 0.42f, 0.42f, 0.22f, 0.5f, 0.08f)
            close()
        }
        // El brillo de abajo hace que se lea agua y no una llama.
        drawArc(background, 110f, 55f, false, at(0.31f, 0.43f), Size(len(0.38f), len(0.38f)), style = outline())
    }
}

@Composable
private fun Moon(color: Color, background: Color, modifier: Modifier) {
    Canvas(modifier) {
        drawCircle(color, len(0.37f), at(0.46f, 0.54f))
        drawCircle(background, len(0.33f), at(0.601f, 0.399f))
        // La estrellita va después del corte, que si no la taparía.
        fillPath(color) {
            move(0.79f, 0.11f)
            bend(0.79f, 0.22f, 0.9f, 0.22f)
            bend(0.79f, 0.22f, 0.79f, 0.33f)
            bend(0.79f, 0.22f, 0.68f, 0.22f)
            bend(0.79f, 0.22f, 0.79f, 0.11f)
            close()
        }
    }
}

@Composable
private fun Pencil(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        // Acostado se mide más fácil; girado queda con la punta abajo a la izquierda.
        val half = 0.105f
        rotate(-45f) {
            fillPath(color) {
                poly(0f, 0.5f, 0.27f, 0.5f - half, 0.27f, 0.5f + half)
                close()
            }
            drawRoundRect(color, at(0.31f, 0.5f - half), Size(len(0.43f), len(2 * half)), CornerRadius(len(0.02f)))
            drawRoundRect(color, at(0.79f, 0.5f - half), Size(len(0.19f), len(2 * half)), CornerRadius(len(0.05f)))
        }
    }
}

@Composable
private fun SpeechBubble(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        // El globo y la colita en un solo trazo, para que la unión no se note.
        strokePath(color) {
            poly(0.44f, 0.74f, 0.22f, 0.9f, 0.27f, 0.74f, 0.22f, 0.74f)
            bend(0.08f, 0.74f, 0.08f, 0.6f)
            line(0.08f, 0.26f)
            bend(0.08f, 0.12f, 0.22f, 0.12f)
            line(0.78f, 0.12f)
            bend(0.92f, 0.12f, 0.92f, 0.26f)
            line(0.92f, 0.6f)
            bend(0.92f, 0.74f, 0.78f, 0.74f)
            close()
        }
        // La A va con líneas porque en un Canvas no hay Text.
        strokePath(color) {
            poly(0.34f, 0.61f, 0.5f, 0.25f, 0.66f, 0.61f)
            poly(0.393f, 0.49f, 0.607f, 0.49f)
        }
    }
}

@Composable
private fun Note(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        // La cabeza inclinada, como en la partitura, y la plica pegada a su borde derecho.
        rotate(-20f, at(0.38f, 0.76f)) {
            drawOval(color, at(0.205f, 0.635f), Size(len(0.35f), len(0.25f)))
        }
        drawLine(color, at(0.517f, 0.73f), at(0.517f, 0.14f), LINE.toPx(), StrokeCap.Round)
        strokePath(color) {
            move(0.517f, 0.14f)
            curve(0.54f, 0.33f, 0.83f, 0.33f, 0.77f, 0.59f)
        }
    }
}

@Composable
private fun Apple(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        fillPath(color) {
            move(0.5f, 0.34f)
            curve(0.6f, 0.24f, 0.88f, 0.22f, 0.88f, 0.52f)
            curve(0.88f, 0.76f, 0.72f, 0.92f, 0.62f, 0.92f)
            curve(0.56f, 0.92f, 0.54f, 0.88f, 0.5f, 0.88f)
            curve(0.46f, 0.88f, 0.44f, 0.92f, 0.38f, 0.92f)
            curve(0.28f, 0.92f, 0.12f, 0.76f, 0.12f, 0.52f)
            curve(0.12f, 0.22f, 0.4f, 0.24f, 0.5f, 0.34f)
            close()
        }
        drawLine(color, at(0.5f, 0.31f), at(0.54f, 0.13f), LINE.toPx(), StrokeCap.Round)
        fillPath(color) {
            move(0.56f, 0.22f)
            curve(0.58f, 0.12f, 0.68f, 0.08f, 0.79f, 0.1f)
            curve(0.77f, 0.2f, 0.67f, 0.24f, 0.56f, 0.22f)
            close()
        }
    }
}

@Composable
private fun Heart(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        fillPath(color) {
            move(0.5f, 0.86f)
            curve(0.3f, 0.7f, 0.08f, 0.56f, 0.08f, 0.36f)
            curve(0.08f, 0.24f, 0.18f, 0.14f, 0.3f, 0.14f)
            curve(0.39f, 0.14f, 0.46f, 0.19f, 0.5f, 0.27f)
            curve(0.54f, 0.19f, 0.61f, 0.14f, 0.7f, 0.14f)
            curve(0.82f, 0.14f, 0.92f, 0.24f, 0.92f, 0.36f)
            curve(0.92f, 0.56f, 0.7f, 0.7f, 0.5f, 0.86f)
            close()
        }
    }
}

/**
 * El fuego de la racha, que no es un hábito: la silueta llena, con una lengua
 * más chica a la izquierda para que se lea fuego y no una gota. Abajo queda
 * la panza ancha, donde va el número de semanas.
 */
@Composable
fun Flame(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        fillPath(color) {
            move(0.56f, 0.03f)
            curve(0.63f, 0.2f, 0.84f, 0.34f, 0.84f, 0.6f)
            curve(0.84f, 0.81f, 0.69f, 0.96f, 0.5f, 0.96f)
            curve(0.31f, 0.96f, 0.16f, 0.81f, 0.16f, 0.62f)
            // La lengua de la izquierda: sube hasta su punta y baja a la muesca.
            curve(0.16f, 0.5f, 0.21f, 0.4f, 0.28f, 0.3f)
            curve(0.3f, 0.39f, 0.34f, 0.44f, 0.4f, 0.47f)
            curve(0.39f, 0.31f, 0.46f, 0.16f, 0.56f, 0.03f)
            close()
        }
    }
}
