package ir.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import ir.pixellab.core.canvas.Axis
import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.canvas.HandleConfig
import ir.pixellab.core.canvas.Handles
import ir.pixellab.core.canvas.SnapGuide
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.LayerBounds
import ir.pixellab.core.model.Vec2
import ir.pixellab.engine.android.AssetSource
import ir.pixellab.engine.android.CanvasSurface
import ir.pixellab.engine.android.FontResolver
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
    fonts: FontResolver,
    assets: AssetSource,
    assetGeneration: Int,
    selection: SelectionOverlay,
    handle: CanvasHandle,
    onGesture: (ir.pixellab.core.canvas.CanvasGesture) -> Unit,
    onSize: (Vec2) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bridge = remember { TouchBridge() }
    // The handle is cleared on dispose so an export queued after the surface goes away fails
    // immediately rather than waiting on a GL thread that no longer exists.
    DisposableEffect(Unit) { onDispose { bridge.reset(); handle.surface = null } }

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
        // The artwork runs the effect pipeline on its own GL thread, so opening a sheet never
        // stalls on a ten-shadow stack.
        AndroidView(
            factory = { CanvasSurface(it).also { surface -> handle.surface = surface } },
            modifier = Modifier.fillMaxSize(),
            update = { surface ->
                surface.fonts = fonts
                surface.assets = assets
                surface.assetGeneration = assetGeneration
                surface.submit(state.document, state.viewport, state.effectsBypassed)
            },
        )

        // Chrome on top, in a separate pass: it must keep a constant size as the canvas zooms, it
        // must not appear in an export, and redrawing a handle must not re-run the effect stack.
        Canvas(Modifier.fillMaxSize()) {
            drawGuides(state)
            drawSelection(state, bounds)
            drawPixelSelection(selection, state.viewport)
        }
    }
}

/** The chosen pixels, as they should appear over the artwork. */
data class SelectionOverlay(val outline: List<ir.pixellab.core.paint.Edge>, val draft: List<Vec2>)

/**
 * Draws the boundary of the chosen pixels.
 *
 * A boundary rather than a tint: a tint hides the artwork exactly where the user is looking, which
 * is the one place it must not. Two passes, dark under light, so the line stays visible over both a
 * white background and a black one — a single-colour marquee disappears on half the artwork people
 * make.
 */
private fun DrawScope.drawPixelSelection(selection: SelectionOverlay, viewport: Viewport) {
    for (edge in selection.outline) {
        val from = viewport.toScreen(edge.from)
        val to = viewport.toScreen(edge.to)
        drawLine(UiColor.Black.copy(alpha = 0.7f), Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = 2f)
        drawLine(UiColor.White, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = 1f)
    }

    // The shape being dragged, before it is committed. Without it a marquee is drawn blind.
    if (selection.draft.size >= 2) {
        val points = selection.draft.map { viewport.toScreen(it) }
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(points.first().x, points.first().y)
            for (point in points.drop(1)) lineTo(point.x, point.y)
        }
        drawPath(
            path,
            UiColor.White,
            style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
        )
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

/** A guide is a canvas-space line; its ends have to travel through the camera like anything else. */
private fun guideEnds(guide: SnapGuide, viewport: Viewport): Pair<Vec2, Vec2> = when (guide.axis) {
    Axis.VERTICAL -> viewport.toScreen(Vec2(guide.position, guide.from)) to
        viewport.toScreen(Vec2(guide.position, guide.to))
    Axis.HORIZONTAL -> viewport.toScreen(Vec2(guide.from, guide.position)) to
        viewport.toScreen(Vec2(guide.to, guide.position))
}
