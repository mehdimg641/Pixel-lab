package ir.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.GradientType
import ir.pixellab.core.render.Luts
import ir.pixellab.core.render.colorFromArgb

/**
 * The gradient editor: a ramp with the stops on it, dragged directly.
 *
 * Direct manipulation rather than a list of positions, because a gradient is judged entirely by
 * eye and the thing being judged has to be under the finger that is adjusting it. A numeric list
 * makes the user look somewhere else to see the result of what they are doing.
 *
 * The ramp is drawn from the same lookup table the renderer samples, not from an approximation
 * built for the panel. If the two disagreed the editor would be lying, and it would be lying most
 * exactly where gradients are hardest to get right — near the midpoint diamonds.
 */
@Composable
fun GradientEditorBody(
    gradient: Fill.Gradient,
    onChange: (Fill.Gradient) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which stop is being edited is a property of this panel, not of the document — selecting a
    // stop is not something anybody wants on the undo stack.
    var selected by remember { mutableStateOf(0) }
    val index = selected.coerceIn(0, gradient.stops.lastIndex)
    val stop = gradient.stops[index]

    fun replace(next: GradientStop) {
        onChange(gradient.copy(stops = gradient.stops.toMutableList().also { it[index] = next }))
    }

    Column(modifier.fillMaxWidth()) {
        GradientRamp(
            gradient = gradient,
            selected = index,
            onSelect = { selected = it },
            onMove = { moved, position ->
                val stops = gradient.stops.toMutableList()
                stops[moved] = stops[moved].copy(position = position)
                onChange(gradient.copy(stops = stops))
            },
        )

        SheetChips {
            for (type in GradientType.entries) {
                SheetChip(type.persianLabel, chosen = gradient.type == type) {
                    onChange(gradient.copy(type = type))
                }
            }
        }

        SheetSection("ایستگاه ${index + 1} از ${gradient.stops.size}")
        SheetSlider("جای ایستگاه", stop.position, 0f..1f, onChange = { value, _ ->
            replace(stop.copy(position = value))
        })
        // Photoshop's little diamond. Imported gradients do not match without it, and it is the
        // control that decides where a two-colour blend actually turns over.
        SheetSlider("نقطهٔ میانی", stop.midpoint, MIDPOINT_MIN..MIDPOINT_MAX, onChange = { value, _ ->
            replace(stop.copy(midpoint = value))
        })

        SheetAction("افزودن ایستگاه") {
            val next = insertion(gradient)
            onChange(gradient.copy(stops = gradient.stops + next))
            selected = gradient.stops.size
        }
        SheetAction(
            "حذف ایستگاه",
            // Two is the floor the model itself enforces; offering a delete that would throw is
            // worse than showing it greyed.
            enabled = gradient.stops.size > MIN_STOPS,
        ) {
            onChange(gradient.copy(stops = gradient.stops.filterIndexed { i, _ -> i != index }))
            selected = 0
        }

        SheetSection("زاویه و پخش")
        SheetSlider("زاویه", gradient.angle, 0f..RAMP_FULL_TURN, onChange = { value, _ ->
            onChange(gradient.copy(angle = value))
        })
        SheetSlider("مقیاس", gradient.scale, RAMP_MIN_SCALE..RAMP_MAX_SCALE, onChange = { value, _ ->
            onChange(gradient.copy(scale = value))
        })
        // A large flat ramp bands visibly at eight bits; a little ordered noise is the standard fix
        // and costs nothing.
        SheetSlider("دیترینگ", gradient.dither, 0f..1f, onChange = { value, _ ->
            onChange(gradient.copy(dither = value))
        })
        SheetChips {
            SheetChip("وارونه", chosen = gradient.reverse) {
                onChange(gradient.copy(reverse = !gradient.reverse))
            }
        }

        SheetSection("رنگ ایستگاه")
        ColorPickerBody(color = stop.color, onChange = { replace(stop.copy(color = it)) })
    }
}

/**
 * The ramp itself, with a handle per stop.
 *
 * A tap selects the nearest stop, a drag moves it. The two gestures are separate detectors rather
 * than one, so a tap that lands between stops selects the nearest one without also nudging it — a
 * combined handler would move a stop by whatever jitter the finger had on the way down.
 */
@Composable
private fun GradientRamp(
    gradient: Fill.Gradient,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, position: Float) -> Unit,
) {
    // Sampled from the renderer's own table, so the panel cannot disagree with the artwork.
    val ramp = remember(gradient) { Luts.gradient(gradient.copy(reverse = false), RAMP_SAMPLES) }
    var dragging by remember { mutableStateOf(-1) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(RAMP_HEIGHT.dp)
            .semantics { contentDescription = "نوار گرادیان با ${gradient.stops.size} ایستگاه" },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(RAMP_HEIGHT.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.ChromeSunken)
                .pointerInput(gradient) {
                    detectTapGestures { offset ->
                        onSelect(nearest(gradient, trackFraction(offset.x, size.width.toFloat())))
                    }
                }
                .pointerInput(gradient) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = nearest(gradient, trackFraction(offset.x, size.width.toFloat()))
                            onSelect(dragging)
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                    ) { change, _ ->
                        if (dragging >= 0) {
                            onMove(dragging, trackFraction(change.position.x, size.width.toFloat()))
                        }
                    }
                },
        ) {
            // Inset so the handles at 0 and 1 are drawn whole. Mapping straight onto the full width
            // puts half of each end handle outside the canvas, and the two stops that are hardest
            // to grab are exactly the two that matter most.
            val inset = SELECTED_HANDLE + RING
            val track = size.width - inset * 2f
            val step = size.width / ramp.size
            for (i in ramp.indices) {
                // Mirrored when reversed rather than resampling: the table is the same either way
                // and drawing it backwards is one subtraction.
                val x = if (gradient.reverse) size.width - (i + 1) * step else i * step
                drawRect(
                    color = UiColor(ramp[i]),
                    topLeft = Offset(x, 0f),
                    size = Size(step + SEAM, size.height * BAR_FRACTION),
                )
            }

            for ((i, stop) in gradient.stops.withIndex()) {
                val position = if (gradient.reverse) 1f - stop.position else stop.position
                val x = inset + position * track
                val y = size.height * BAR_FRACTION + HANDLE_GAP
                val radius = if (i == selected) SELECTED_HANDLE else HANDLE
                // Drawn as a ring over a filled disc of the stop's own colour, so the handle shows
                // what it carries — a plain marker would make two dark stops indistinguishable.
                drawCircle(UiColor(0xFF000000), radius + RING, Offset(x, y))
                drawCircle(UiColor.White, radius + RING * HALF, Offset(x, y))
                drawCircle(
                    UiColor(
                        red = stop.color.r,
                        green = stop.color.g,
                        blue = stop.color.b,
                        alpha = 1f,
                    ),
                    radius,
                    Offset(x, y),
                )
            }
        }
    }
}

/**
 * A touch x as a fraction of the inset track.
 *
 * The same inset the handles are drawn with, so a finger placed on a handle reports that handle's
 * own position — without it, dragging an end stop makes it jump by half a handle's width before it
 * starts to follow.
 */
private fun trackFraction(x: Float, width: Float): Float {
    val inset = SELECTED_HANDLE + RING
    val track = (width - inset * 2f).coerceAtLeast(1f)
    return ((x - inset) / track).coerceIn(0f, 1f)
}

/** The stop nearest a normalised x, which is what a tap anywhere on the ramp means. */
private fun nearest(gradient: Fill.Gradient, at: Float): Int {
    val target = if (gradient.reverse) 1f - at else at
    return gradient.stops.indices.minByOrNull { kotlin.math.abs(gradient.stops[it].position - target) } ?: 0
}

/**
 * Where a new stop goes: the middle of the widest gap, carrying the colour already there.
 *
 * Both halves matter. The widest gap is where there is room for one, and taking the colour from the
 * ramp means adding a stop changes nothing until it is moved — an inserted stop that arrived black
 * would put a band across the gradient every time.
 */
private fun insertion(gradient: Fill.Gradient): GradientStop {
    val sorted = gradient.stops.sortedBy { it.position }
    var at = HALF
    var widest = -1f
    for (i in 0 until sorted.size - 1) {
        val gap = sorted[i + 1].position - sorted[i].position
        if (gap > widest) {
            widest = gap
            at = (sorted[i].position + sorted[i + 1].position) * HALF
        }
    }
    val sample = Luts.gradient(gradient.copy(reverse = false), RAMP_SAMPLES)
    val argb = sample[(at * (RAMP_SAMPLES - 1)).toInt().coerceIn(0, RAMP_SAMPLES - 1)]
    return GradientStop(position = at, color = colorFromArgb(argb))
}

private val GradientType.persianLabel: String
    get() = when (this) {
        GradientType.LINEAR -> "خطی"
        GradientType.RADIAL -> "شعاعی"
        GradientType.ANGULAR -> "زاویه‌ای"
        GradientType.REFLECTED -> "بازتابی"
        GradientType.DIAMOND -> "لوزی"
    }

private const val RAMP_SAMPLES = 256
private const val RAMP_HEIGHT = 68
private const val BAR_FRACTION = 0.62f
private const val SEAM = 0.75f

private const val HANDLE = 9f
private const val SELECTED_HANDLE = 12f
private const val RING = 3f
private const val HANDLE_GAP = 14f

private const val HALF = 0.5f


/** Photoshop's own limits on the diamond: it may not reach either stop, or the blend has no width. */
private const val MIDPOINT_MIN = 0.05f
private const val MIDPOINT_MAX = 0.95f

private const val MIN_STOPS = 2
private const val RAMP_FULL_TURN = 360f
private const val RAMP_MIN_SCALE = 0.1f
private const val RAMP_MAX_SCALE = 4f
