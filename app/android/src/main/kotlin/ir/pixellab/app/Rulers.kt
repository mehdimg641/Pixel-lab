package ir.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ir.pixellab.core.canvas.Ruler
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Guide
import ir.pixellab.core.model.Vec2

/**
 * The rulers, and the strip a guide is dragged out of.
 *
 * The tick spacing comes from the zoom rather than being fixed, because a ruler with a fixed step is
 * an unreadable smear at one zoom and shows two numbers at another. The 1-2-5 sequence behind it is
 * what every ruler uses, for the reason that those are the numbers people can divide in their head.
 *
 * Dragging a guide out of the ruler is the gesture everyone already knows, and on a phone it is
 * better than a button: the guide arrives *where the finger is*, so placing it takes one motion
 * instead of adding one and then dragging it into position.
 */
@Composable
fun Rulers(
    state: EditorState,
    model: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    if (!state.showRulers) return
    val density = LocalDensity.current
    val thickness = with(density) { RULER_THICKNESS.dp.toPx() }

    Box(modifier.fillMaxWidth()) {
        RulerStrip(state, model, vertical = false, thickness = thickness)
        RulerStrip(state, model, vertical = true, thickness = thickness)
        // The corner where the two meet: left blank rather than showing a tick, because a number
        // there would belong to neither axis.
        Box(
            Modifier
                .size(RULER_THICKNESS.dp)
                .background(Ink.Chrome),
        )
    }
}

@Composable
private fun RulerStrip(
    state: EditorState,
    model: EditorViewModel,
    vertical: Boolean,
    thickness: Float,
) {
    // Which guide this drag is carrying. Held across the gesture so a drag started on the ruler
    // keeps moving the same guide even after the finger leaves the strip.
    var dragging by remember { mutableStateOf<Int?>(null) }

    Canvas(
        Modifier
            .then(
                if (vertical) {
                    Modifier.width(RULER_THICKNESS.dp).fillMaxHeight().padding(top = RULER_THICKNESS.dp)
                } else {
                    Modifier.fillMaxWidth().height(RULER_THICKNESS.dp).padding(start = RULER_THICKNESS.dp)
                },
            )
            .background(Ink.Chrome)
            .pointerInput(vertical, state.viewport) {
                detectDragGestures(
                    onDragStart = { start ->
                        // The strip is offset by its own thickness, and the guide must land under
                        // the finger rather than a ruler's width away from it.
                        val screen = if (vertical) {
                            Vec2(thickness + start.x, start.y + thickness)
                        } else {
                            Vec2(start.x + thickness, thickness + start.y)
                        }
                        val canvas = state.viewport.toCanvas(screen)
                        dragging = model.mutate {
                            addGuide(
                                Guide(
                                    vertical = vertical,
                                    position = if (vertical) canvas.x else canvas.y,
                                ),
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        val index = dragging ?: return@detectDragGestures
                        val screen = Vec2(change.position.x + thickness, change.position.y + thickness)
                        val canvas = state.viewport.toCanvas(screen)
                        model.act {
                            moveGuide(index, if (vertical) canvas.x else canvas.y, continuous = true)
                        }
                    },
                    onDragEnd = {
                        val index = dragging ?: return@detectDragGestures
                        dragging = null
                        model.act { endScrub() }
                        // Dropped back outside the canvas means "I did not want this after all",
                        // which is what dragging a guide off the artboard means everywhere else.
                        val guide = model.state.document.guides.getOrNull(index) ?: return@detectDragGestures
                        val extent = if (vertical) {
                            model.state.document.canvas.width
                        } else {
                            model.state.document.canvas.height
                        }
                        if (guide.position < 0f || guide.position > extent) {
                            model.act { removeGuide(index) }
                        }
                    },
                    onDragCancel = {
                        dragging?.let { index -> model.act { removeGuide(index) } }
                        dragging = null
                    },
                )
            },
    ) {
        drawTicks(state, vertical, thickness)
    }
}

private fun DrawScope.drawTicks(state: EditorState, vertical: Boolean, thickness: Float) {
    val zoom = state.viewport.zoom
    val step = Ruler.stepFor(zoom)
    val canvas = state.document.canvas
    val extent = if (vertical) canvas.height.toFloat() else canvas.width.toFloat()

    for (at in Ruler.ticks(extent, step)) {
        val screen = state.viewport.toScreen(
            if (vertical) Vec2(0f, at) else Vec2(at, 0f),
        )
        // The strip is drawn shifted by its own thickness, so a tick's position within it is the
        // screen coordinate minus that offset.
        val along = if (vertical) screen.y - thickness else screen.x - thickness
        if (along < 0f || along > (if (vertical) size.height else size.width)) continue

        val length = thickness * TICK_FRACTION
        if (vertical) {
            drawLine(
                color = Ink.TextMuted,
                start = Offset(size.width - length, along),
                end = Offset(size.width, along),
                strokeWidth = 1f,
            )
        } else {
            drawLine(
                color = Ink.TextMuted,
                start = Offset(along, size.height - length),
                end = Offset(along, size.height),
                strokeWidth = 1f,
            )
        }
    }

    // A hairline along the inner edge, so the strip reads as a ruler rather than as a bar that
    // happens to have marks on it.
    if (vertical) {
        drawLine(
            UiColor(0x33FFFFFF),
            Offset(size.width, 0f),
            Offset(size.width, size.height),
            strokeWidth = 1f,
        )
    } else {
        drawLine(
            UiColor(0x33FFFFFF),
            Offset(0f, size.height),
            Offset(size.width, size.height),
            strokeWidth = 1f,
        )
    }
}

/** Wide enough to drag from with a thumb, narrow enough not to eat the canvas on a phone. */
private const val RULER_THICKNESS = 18

/** Ticks reach two-thirds across the strip; a full-width tick reads as a grid, not a ruler. */
private const val TICK_FRACTION = 0.66f
