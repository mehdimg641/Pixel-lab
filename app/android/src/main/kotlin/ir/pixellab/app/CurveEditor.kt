package ir.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.render.Luts
import kotlin.math.abs

/**
 * The curve editor.
 *
 * The single most powerful colour control there is, and the one that has to be usable with a thumb.
 * Three decisions carry it:
 *
 * A tap adds a point, a drag moves the nearest one, and a long press removes it. Requiring a mode
 * switch to delete — the desktop convention of alt-clicking — has no thumb equivalent, and a curve
 * you can only add to becomes unusable after four taps.
 *
 * Points snap nowhere and are clamped only at the ends. A curve editor that snapped to a grid would
 * make the fine highlight roll — the whole reason to reach for curves — impossible to set.
 *
 * The rendered line comes from the same lookup table the GPU samples, so the picture on screen is
 * the correction, not an approximation of it.
 */
@Composable
fun CurveEditor(
    curve: Curve,
    onChange: (Curve) -> Unit,
    modifier: Modifier = Modifier,
    channel: CurveChannel = CurveChannel.COMPOSITE,
) {
    var box by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.ChromeSunken)
            .pointerInput(curve) {
                detectTapGestures(
                    onTap = { at ->
                        val point = toCurve(at, size.width.toFloat(), size.height.toFloat())
                        onChange(curve.withPointAdded(point))
                    },
                    onLongPress = { at ->
                        val point = toCurve(at, size.width.toFloat(), size.height.toFloat())
                        onChange(curve.withNearestRemoved(point))
                    },
                )
            }
            .pointerInput(curve) {
                var dragging = -1
                detectDragGestures(
                    onDragStart = { at ->
                        dragging = curve.nearestIndex(toCurve(at, size.width.toFloat(), size.height.toFloat()))
                    },
                    onDragEnd = { dragging = -1 },
                ) { change, _ ->
                    if (dragging < 0) return@detectDragGestures
                    val point = toCurve(change.position, size.width.toFloat(), size.height.toFloat())
                    onChange(curve.withPointMoved(dragging, point))
                }
            },
    ) {
        box = Offset(size.width, size.height)

        // A quarter grid, which is what a person actually reads a curve against: shadows, midtones,
        // highlights. A finer grid competes with the line for attention.
        for (i in 1 until GRID) {
            val at = size.width * i / GRID
            drawLine(Ink.Divider, Offset(at, 0f), Offset(at, size.height), strokeWidth = 1f)
            drawLine(Ink.Divider, Offset(0f, at), Offset(size.width, at), strokeWidth = 1f)
        }
        drawLine(
            Ink.Divider,
            Offset(0f, size.height),
            Offset(size.width, 0f),
            strokeWidth = 1f,
        )

        // Drawn from the same table the shader samples, so what is on screen is the correction
        // itself rather than a second implementation that can drift from it.
        val table = Luts.curve(curve, TABLE_SIZE)
        val path = Path().apply {
            moveTo(0f, size.height * (1f - table[0].coerceIn(0f, 1f)))
            for (i in 1 until TABLE_SIZE) {
                lineTo(
                    size.width * i / (TABLE_SIZE - 1f),
                    size.height * (1f - table[i].coerceIn(0f, 1f)),
                )
            }
        }
        drawPath(path, channel.tint, style = Stroke(width = 2.5f))

        for (point in curve.points) {
            val at = Offset(point.x * size.width, (1f - point.y) * size.height)
            drawCircle(UiColor.White, radius = 8f, center = at)
            drawCircle(channel.tint, radius = 8f, center = at, style = Stroke(width = 2f))
        }
    }
}

/** Which channel the editor is showing, for the line's colour and the picker above it. */
enum class CurveChannel(val label: String, val tint: UiColor) {
    COMPOSITE("ترکیبی", UiColor.White),
    RED("قرمز", UiColor(0xFFFF5A5A)),
    GREEN("سبز", UiColor(0xFF5AE07A)),
    BLUE("آبی", UiColor(0xFF5A9CFF)),
}

@Composable
fun CurveChannelRow(current: CurveChannel, onPick: (CurveChannel) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (channel in CurveChannel.entries) {
            Text(
                channel.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (channel == current) channel.tint else Ink.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (channel == current) Ink.Chrome else UiColor.Transparent)
                    .clickable { onPick(channel) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private fun toCurve(at: Offset, width: Float, height: Float) = Vec2(
    (at.x / width).coerceIn(0f, 1f),
    (1f - at.y / height).coerceIn(0f, 1f),
)

/**
 * Inserts a point in its place along the input axis.
 *
 * Appending instead would let the list go out of order, and the evaluator walks it assuming it is
 * sorted — the curve would then fold back on itself and the correction would be nonsense.
 */
internal fun Curve.withPointAdded(point: Vec2): Curve {
    val updated = (points + point).sortedBy { it.x }
    return Curve(updated)
}

internal fun Curve.withPointMoved(index: Int, point: Vec2): Curve {
    if (index !in points.indices) return this
    // The two ends keep their input value: a curve whose first point slid inwards would leave the
    // darkest shadows undefined, and the evaluator would clamp them into a flat black band.
    val clamped = when (index) {
        0 -> Vec2(0f, point.y)
        points.lastIndex -> Vec2(1f, point.y)
        else -> point
    }
    return Curve(points.toMutableList().also { it[index] = clamped }.sortedBy { it.x })
}

/** Removes the point nearest the touch, keeping the two ends. */
internal fun Curve.withNearestRemoved(point: Vec2): Curve {
    if (points.size <= 2) return this
    val index = nearestIndex(point)
    if (index <= 0 || index >= points.lastIndex) return this
    return Curve(points.toMutableList().also { it.removeAt(index) })
}

internal fun Curve.nearestIndex(point: Vec2): Int {
    var best = 0
    var bestDistance = Float.MAX_VALUE
    for ((i, candidate) in points.withIndex()) {
        // Squared distance in curve space, which is square, so no axis is favoured.
        val distance = abs(candidate.x - point.x) * abs(candidate.x - point.x) +
            abs(candidate.y - point.y) * abs(candidate.y - point.y)
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}

private const val GRID = 4
private const val TABLE_SIZE = 64
