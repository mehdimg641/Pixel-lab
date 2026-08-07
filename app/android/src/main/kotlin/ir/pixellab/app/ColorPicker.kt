package ir.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ir.pixellab.core.model.Color
import kotlin.math.roundToInt

/**
 * The colour picker.
 *
 * A saturation–value square under a hue strip, not a wheel. A wheel looks better in a screenshot and
 * is worse to use with a thumb: it puts the most-used region — near-neutral, mid-value — at the
 * centre where the finger covers it, and it wastes a third of its area on angles the user is not
 * choosing between.
 *
 * Hex entry is here because it is how a brand colour actually arrives: from a style guide, as six
 * characters. A picker without it makes matching an exact colour a game of nudging a dot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerBody(color: Color, onChange: (Color) -> Unit, modifier: Modifier = Modifier) {
    // Held as HSV rather than derived from the colour each recomposition, because a fully black or
    // fully desaturated colour has no hue to recover — and the cursor would jump to red the moment
    // the user dragged the value to zero.
    var hsv by remember { mutableStateOf(color.toHsv()) }
    var hex by remember { mutableStateOf(color.toHex()) }

    fun emit(next: Triple<Float, Float, Float>) {
        hsv = next
        val updated = fromHsv(next.first, next.second, next.third, color.a)
        hex = updated.toHex()
        onChange(updated)
    }

    Column(modifier.fillMaxWidth().padding(12.dp)) {
        SaturationValueField(hsv) { s, v -> emit(Triple(hsv.first, s, v)) }

        HueStrip(hsv.first) { emit(Triple(it, hsv.second, hsv.third)) }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(UiColor(color.r, color.g, color.b, 1f))
                    .border(1.dp, Ink.Divider, RoundedCornerShape(8.dp)),
            )
            BasicTextField(
                value = hex,
                onValueChange = { typed ->
                    hex = typed
                    // Applied only once it is a whole colour, so a half-typed value does not
                    // repaint the artwork with whatever three characters happen to parse.
                    parseHex(typed)?.let { parsed ->
                        hsv = parsed.toHsv()
                        onChange(parsed.copy(a = color.a))
                    }
                },
                singleLine = true,
                cursorBrush = SolidColor(Ink.Accent),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.Text),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "کد رنگ" }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ink.Chrome),
                // Inside the decoration, not around the field — the well was 40dp tall and only
                // the 20dp of it holding the six characters answered a tap.
                decorationBox = { field ->
                    Box(
                        Modifier.heightIn(min = Space.touch).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { field() }
                },
            )
        }

        ColorSwatches(
            swatches = BASIC_SWATCHES,
            current = color,
            modifier = Modifier.padding(top = Space.small),
        ) { swatch ->
            hsv = swatch.toHsv()
            hex = swatch.toHex()
            onChange(swatch.copy(a = color.a))
        }
    }
}

@Composable
private fun SaturationValueField(hsv: Triple<Float, Float, Float>, onChange: (Float, Float) -> Unit) {
    var size by remember { mutableStateOf(Offset.Zero) }
    val hueColor = fromHsv(hsv.first, 1f, 1f, 1f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                fun report(at: Offset) {
                    onChange(
                        (at.x / this.size.width).coerceIn(0f, 1f),
                        1f - (at.y / this.size.height).coerceIn(0f, 1f),
                    )
                }
                detectTapGestures(onPress = { report(it) })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange(
                        (change.position.x / this.size.width).coerceIn(0f, 1f),
                        1f - (change.position.y / this.size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        size = Offset(this.size.width, this.size.height)
        // Saturation across, value down, exactly as every design tool draws it — familiarity is
        // worth more here than any rearrangement.
        drawRect(
            Brush.horizontalGradient(
                listOf(UiColor.White, UiColor(hueColor.r, hueColor.g, hueColor.b, 1f)),
            ),
        )
        drawRect(Brush.verticalGradient(listOf(UiColor.Transparent, UiColor.Black)))

        val cursor = Offset(hsv.second * this.size.width, (1f - hsv.third) * this.size.height)
        // Two rings, dark under light, so the cursor stays visible over both a white corner and a
        // black one — a single-colour cursor disappears in one of the two.
        drawCircle(UiColor.Black, radius = 11f, center = cursor, style = Stroke(width = 3f))
        drawCircle(UiColor.White, radius = 11f, center = cursor, style = Stroke(width = 1.5f))
    }
}

@Composable
private fun HueStrip(hue: Float, onChange: (Float) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = { onChange((it.x / size.width).coerceIn(0f, 1f)) })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val stops = (0..HUE_STOPS).map { i ->
            val c = fromHsv(i.toFloat() / HUE_STOPS, 1f, 1f, 1f)
            UiColor(c.r, c.g, c.b, 1f)
        }
        drawRect(Brush.horizontalGradient(stops))
        val x = hue * size.width
        drawLine(UiColor.Black, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
        drawLine(UiColor.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    }
}

// ---- conversions ---------------------------------------------------------------------------

internal fun Color.toHsv(): Triple<Float, Float, Float> {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta == 0f -> 0f
        max == r -> ((g - b) / delta / 6f + 1f) % 1f
        max == g -> ((b - r) / delta + 2f) / 6f
        else -> ((r - g) / delta + 4f) / 6f
    }
    return Triple(h, if (max == 0f) 0f else delta / max, max)
}

internal fun fromHsv(h: Float, s: Float, v: Float, a: Float): Color {
    val i = (h * 6f).toInt()
    val f = h * 6f - i
    val p = v * (1f - s)
    val q = v * (1f - f * s)
    val t = v * (1f - (1f - f) * s)
    return when (((i % 6) + 6) % 6) {
        0 -> Color(v, t, p, a)
        1 -> Color(q, v, p, a)
        2 -> Color(p, v, t, a)
        3 -> Color(p, q, v, a)
        4 -> Color(t, p, v, a)
        else -> Color(v, p, q, a)
    }
}

internal fun Color.toHex(): String {
    fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt().toString(16).padStart(2, '0')
    return "#${channel(r)}${channel(g)}${channel(b)}".uppercase()
}

/**
 * Reads a hex colour.
 *
 * Three-digit shorthand is accepted because that is how a lot of style guides write greys and
 * primaries, and rejecting it makes the field feel broken to anyone who has typed `#fff` before.
 */
internal fun parseHex(text: String): Color? {
    val digits = text.trim().removePrefix("#")
    val expanded = when (digits.length) {
        3 -> digits.map { "$it$it" }.joinToString("")
        6 -> digits
        else -> return null
    }
    val value = expanded.toLongOrNull(16) ?: return null
    return Color(
        ((value shr 16) and 0xFF) / 255f,
        ((value shr 8) and 0xFF) / 255f,
        (value and 0xFF) / 255f,
    )
}


private const val HUE_STOPS = 12

