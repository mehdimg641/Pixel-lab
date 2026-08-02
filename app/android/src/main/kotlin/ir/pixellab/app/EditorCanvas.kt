package ir.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import ir.pixellab.core.canvas.Axis
import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.canvas.HandleConfig
import ir.pixellab.core.canvas.Handles
import ir.pixellab.core.canvas.SnapGuide
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.LayerBounds
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Vec2
import ir.pixellab.engine.android.TouchBridge

/**
 * The canvas: artwork, selection chrome and touch handling.
 *
 * Chrome is drawn **above** the artwork in a separate pass rather than inside the render graph. It
 * has to stay a constant size as the canvas zooms, it must not appear in an export, and redrawing a
 * handle must not re-run a ten-shadow effect stack. Compositing it into the same pipeline as the
 * artwork would give up all three.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun EditorCanvas(
    state: EditorState,
    bounds: LayerBounds,
    onGesture: (ir.pixellab.core.canvas.CanvasGesture) -> Unit,
    onSize: (Vec2) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bridge = remember { TouchBridge() }
    DisposableEffect(Unit) { onDispose { bridge.reset() } }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { onSize(Vec2(it.width.toFloat(), it.height.toFloat())) }
            .pointerInteropFilter { event ->
                bridge.onTouchEvent(event).forEach(onGesture)
                // A long press is the absence of events, so it is polled from the same stream's
                // timestamps rather than from a frame callback.
                bridge.onFrame(event.eventTime).forEach(onGesture)
                true
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawSurround()
            drawCanvasSheet(state)
            drawArtwork(state, bounds)
            drawGuides(state)
            drawSelection(state, bounds)
        }
    }
}

/** Neutral grey around the artboard; a tint here would shift how the artwork's colours read. */
private fun DrawScope.drawSurround() {
    drawRect(UiColor(0xFF2A2A2A))
}

private fun DrawScope.drawCanvasSheet(state: EditorState) {
    val sheet = state.viewport.screenBounds(state.document.canvas.size)
    drawRect(
        color = UiColor.White,
        topLeft = Offset(sheet.left, sheet.top),
        size = Size(sheet.width, sheet.height),
    )
    drawRect(
        color = UiColor(0xFF3C3C3C),
        topLeft = Offset(sheet.left, sheet.top),
        size = Size(sheet.width, sheet.height),
        style = Stroke(width = 1f),
    )
}

/**
 * A placeholder pass for the artwork.
 *
 * The GL pipeline in `core:render` composites the real thing once layers can be rasterised to
 * textures. Drawing the silhouettes here in the meantime keeps selection, handles and snapping
 * exercisable against something visible, and the swap is confined to this one function.
 */
private fun DrawScope.drawArtwork(state: EditorState, bounds: LayerBounds) {
    for (layer in state.document.walk()) {
        if (!layer.visible || layer is Layer.Group) continue
        val corners = cornersOf(layer, bounds, state.viewport)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
            close()
        }
        drawPath(path, UiColor(0xFF1A1A1A), alpha = layer.opacity)
    }
}

private fun DrawScope.drawGuides(state: EditorState) {
    for (guide in state.guides) {
        val (start, end) = guideEnds(guide, state.viewport)
        drawLine(
            color = UiColor(0xFFFF3FB4),
            start = Offset(start.x, start.y),
            end = Offset(end.x, end.y),
            strokeWidth = 1.5f,
        )
    }
}

private fun DrawScope.drawSelection(state: EditorState, bounds: LayerBounds) {
    val config = HandleConfig()
    for (layer in state.selectedLayers) {
        val layout = Handles.layout(bounds.of(layer), layer.transform, state.viewport, config)
        val outline = androidx.compose.ui.graphics.Path().apply {
            val corners = layout.corners
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
            close()
        }
        drawPath(
            outline,
            UiColor(0xFF4C8DFF),
            style = Stroke(
                width = 1.5f,
                // Dashed, so the outline stays visible over artwork of any colour.
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
            ),
        )

        // Only the primary selection gets grabbable handles; drawing them on every member of a
        // multi-selection would be a screen full of dots that do nothing.
        if (layer.id != state.selection.primary) continue
        for ((handle, position) in layout.positions) {
            if (handle == Handle.BODY) continue
            val radius = if (handle == Handle.ROTATE) 9f else 7f
            drawCircle(UiColor.White, radius, Offset(position.x, position.y))
            drawCircle(UiColor(0xFF4C8DFF), radius, Offset(position.x, position.y), style = Stroke(2f))
        }
        val rotate = layout.positions[Handle.ROTATE]
        val top = layout.positions[Handle.TOP]
        if (rotate != null && top != null) {
            drawLine(
                UiColor(0xFF4C8DFF),
                Offset(top.x, top.y),
                Offset(rotate.x, rotate.y),
                strokeWidth = 1.5f,
            )
        }
    }
}

private fun cornersOf(layer: Layer, bounds: LayerBounds, viewport: Viewport): List<Vec2> {
    val local = bounds.of(layer)
    return listOf(
        Vec2(local.left, local.top), Vec2(local.right, local.top),
        Vec2(local.right, local.bottom), Vec2(local.left, local.bottom),
    ).map { viewport.toScreen(Handles.localToCanvas(it, local, layer.transform)) }
}

/** A guide is a canvas-space line; its ends have to travel through the camera like anything else. */
private fun guideEnds(guide: SnapGuide, viewport: Viewport): Pair<Vec2, Vec2> = when (guide.axis) {
    Axis.VERTICAL -> viewport.toScreen(Vec2(guide.position, guide.from)) to
        viewport.toScreen(Vec2(guide.position, guide.to))
    Axis.HORIZONTAL -> viewport.toScreen(Vec2(guide.from, guide.position)) to
        viewport.toScreen(Vec2(guide.to, guide.position))
}
