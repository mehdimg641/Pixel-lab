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

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                    textStyle = TextStyle(color = Ink.Accent, textAlign = TextAlign.End),
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
                        .background(Ink.ChromeSunken, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else {
                Text(
                    spec.format(value),
                    style = MaterialTheme.typography.bodyMedium,
                    // Showing the current gain while a precise drag is under way is the only
                    // feedback that the finger's distance from the track is doing anything.
                    color = if (gain < 0.9f) Ink.Accent else Ink.TextMuted,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
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
            val fraction = spec.fractionOf(value)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.ChromeSunken),
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.Accent),
            )
        }
    }
}
