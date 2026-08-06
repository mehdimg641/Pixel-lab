package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.SliderDrag
import ir.pixellab.core.editor.format
import ir.pixellab.core.editor.fractionOf
import ir.pixellab.core.editor.parseEntry
import ir.pixellab.core.render.ParameterSpec

/**
 * A slider you can work with rather than only explore with.
 *
 * Four behaviours, all of them from [SliderDrag] and none invented here:
 *
 * - Dragging **away** from the track magnifies precision, so 37 is reachable on a 0–500 range that
 *   is only three hundred pixels wide.
 * - A **long press** turns the control into a numeric field. Professional work needs exact values —
 *   the reference PSDs contain a bevel depth of 317%.
 * - A **double tap** returns to the default.
 * - The whole drag is **one undo entry**, which is why [onChange] carries `continuous`.
 */
@Composable
fun PrecisionSlider(
    spec: ParameterSpec.Slider,
    value: Float,
    onChange: (value: Float, continuous: Boolean) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidth by remember { mutableStateOf(0f) }
    var editing by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf("") }
    var gain by remember { mutableStateOf(1f) }

    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.small)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(spec.label, style = MaterialTheme.typography.bodyMedium, color = Ink.Text)
            if (editing) {
                BasicTextField(
                    value = entry,
                    onValueChange = { entry = it },
                    singleLine = true,
                    textStyle = NumericStyle.copy(color = Ink.Accent, textAlign = TextAlign.End),
                    cursorBrush = SolidColor(Ink.Accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            spec.parseEntry(entry)?.let { onChange(it, false) }
                            onCommit()
                            editing = false
                        },
                    ),
                    modifier = Modifier
                        .background(Ink.ChromeSunken, Corners.button)
                        .padding(horizontal = Space.small, vertical = Space.tight),
                )
            } else {
                // Through [Numeric], so the readout carries `tnum`. Without tabular figures the
                // number shifts sideways as it counts, under the very finger that is changing it.
                Numeric(
                    spec.format(value),
                    // Showing the current gain while a precise drag is under way is the only
                    // feedback that the finger's distance from the track is doing anything.
                    tone = if (gain < PRECISE) Ink.Accent else Ink.TextMuted,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(TOUCH_STRIP)
                .onSizeChanged { trackWidth = it.width.toFloat() }
                .pointerInput(spec.key, value, trackWidth) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val drag = SliderDrag(
                            spec = spec,
                            startValue = value,
                            startScreen = down.position.x to down.position.y,
                            trackWidth = trackWidth,
                            trackY = size.height / 2f,
                            // The interface is mirrored, so dragging towards the leading edge —
                            // which is the left — has to raise the value.
                            rightToLeft = true,
                        )
                        var moved = false
                        while (true) {
                            // Written out rather than using detectDragGestures, which reports
                            // movement only along the drag axis — and the precision behaviour
                            // depends entirely on how far the finger has strayed *across* it.
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            val at = change.position
                            moved = true
                            gain = drag.gainAt(at.y)
                            onChange(drag.valueAt(at.x, at.y), true)
                        }
                        gain = 1f
                        // A press that never moved is a request to type the value, which is the
                        // only way to reach an exact number on a three-hundred-pixel track.
                        if (moved) onCommit() else entry = spec.format(value).also { editing = true }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = spec.fractionOf(value).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK)
                    .clip(RoundedCornerShape(TRACK / 2))
                    .background(Ink.ChromeSunken),
            )
            // The filled part and the knob in one box: the knob sits at its *end*, which resolves to
            // the correct side in either direction without the layout knowing which one it is in.
            Box(
                Modifier.fillMaxWidth(fraction),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(TRACK)
                        .clip(RoundedCornerShape(TRACK / 2))
                        .background(Ink.Accent),
                )
                // A track with no knob does not read as draggable — it reads as a progress bar, and
                // people wait for it rather than touching it. This is the whole reason the control
                // looked inert in the interface this replaces.
                Box(
                    Modifier
                        .size(KNOB)
                        .clip(RoundedCornerShape(KNOB / 2))
                        .background(if (gain < PRECISE) Ink.Accent else Ink.Text),
                )
            }
        }
    }
}

/** Thin, because the knob is what the eye finds and a heavy track competes with it. */
private val TRACK = 4.dp

/**
 * The band the finger may land on.
 *
 * [Space.touch], not the 36dp it was. "Comfortably over the 4dp track" was the wrong comparison —
 * the target is measured against the finger, not against the graphic it draws. This is the second
 * most-tapped control in the application after the chip: every effect parameter, every adjustment,
 * every brush setting is one of these, and a miss here does nothing at all rather than doing the
 * wrong thing, which is the failure mode people read as "the app is not responding".
 */
private val TOUCH_STRIP = Space.touch

/** Small enough not to hide the value it points at, large enough to see against a busy sheet. */
private val KNOB = 16.dp

/** Below this gain the drag has left the track and is in its magnified mode. */
private const val PRECISE = 0.9f
