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
    pen: PenOverlay,
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
            // Beneath everything else: the grid and the safe zone are a backdrop to work against,
            // and drawing them over the artwork would make a busy photograph unreadable.
            drawGrid(state)
            drawSafeZone(state)
            drawUserGuides(state)
            drawGuides(state)
            drawSelection(state, bounds)
            drawPixelSelection(selection, state.viewport)
            drawPenPath(pen, state.viewport)
        }
    }
}

/** The path being drawn, and which of its nodes is showing its handles. */
data class PenOverlay(
    val path: ir.pixellab.core.model.ShapeGeometry.Path,
    val active: ir.pixellab.core.vector.NodeRef?,
)

/**
 * Draws the path under construction.
 *
 * Not through the renderer: the path is not a layer yet, and it has to be visible before it is a
 * closed shape — a contour of two nodes has no fill to render at all. Chrome is also the right
 * place for it, because the nodes and handles must stay a constant size as the canvas zooms.
 */
private fun DrawScope.drawPenPath(pen: PenOverlay, viewport: Viewport) {
    for (points in ir.pixellab.core.vector.PathMath.flatten(pen.path)) {
        if (points.size < 2) continue
        val screen = points.map { viewport.toScreen(it) }
        val outline = androidx.compose.ui.graphics.Path().apply {
            moveTo(screen.first().x, screen.first().y)
            for (point in screen.drop(1)) lineTo(point.x, point.y)
        }
        // Dark under light, so the line reads over both a white background and a black one.
        drawPath(outline, UiColor.Black.copy(alpha = 0.6f), style = Stroke(width = 3f))
        drawPath(outline, UiColor(0xFF4C8DFF), style = Stroke(width = 1.5f))
    }

    for ((c, contour) in pen.path.contours.withIndex()) {
        for ((n, node) in contour.nodes.withIndex()) {
            val at = viewport.toScreen(node.point)
            val isActive = pen.active?.contour == c && pen.active?.node == n

            if (isActive) {
                // Handles are shown for the selected node only. Showing every node's would bury the
                // path itself under its own controls on anything with more than a few points.
                for (control in listOf(node.controlIn, node.controlOut)) {
                    val handle = viewport.toScreen(control)
                    drawLine(UiColor(0xFF4C8DFF), Offset(at.x, at.y), Offset(handle.x, handle.y), strokeWidth = 1.5f)
                    drawCircle(UiColor.White, 6f, Offset(handle.x, handle.y))
                    drawCircle(UiColor(0xFF4C8DFF), 6f, Offset(handle.x, handle.y), style = Stroke(1.5f))
                }
            }
            drawCircle(if (isActive) UiColor(0xFF4C8DFF) else UiColor.White, 6f, Offset(at.x, at.y))
            drawCircle(UiColor.Black.copy(alpha = 0.5f), 6f, Offset(at.x, at.y), style = Stroke(1.5f))
        }
    }
}

/** The chosen pixels, as they should appear over the artwork. */
data class SelectionOverlay(
    val outline: List<ir.pixellab.core.paint.Edge>,
    val draft: List<Vec2>,
    /**
     * The selection itself, drawn as a translucent wash when Quick Mask is on.
     *
     * Null the rest of the time. Marching ants cannot show a *partial* selection — a feathered edge,
     * a gradient mask, the soft boundary every tool here produces all render as one line at the fifty
     * per cent mark — so Quick Mask exists to draw the coverage instead, and this is what it draws.
     */
    val mask: ir.pixellab.core.paint.PixelSelection? = null,
)

/**
 * Draws the boundary of the chosen pixels.
 *
 * A boundary rather than a tint: a tint hides the artwork exactly where the user is looking, which
 * is the one place it must not. Two passes, dark under light, so the line stays visible over both a
 * white background and a black one — a single-colour marquee disappears on half the artwork people
 * make.
 */
private fun DrawScope.drawPixelSelection(selection: SelectionOverlay, viewport: Viewport) {
    selection.mask?.let { drawQuickMask(it, viewport) }

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

/**
 * The Quick Mask wash.
 *
 * Red at half opacity over what is **not** selected, which is Photoshop's convention and worth
 * keeping for a reason beyond familiarity: the protected area is the one a user is trying to judge
 * the shape of, and tinting the selected part instead would hide the artwork they are selecting.
 *
 * Sampled on a grid rather than drawn per pixel. A full-resolution mask on a six-megapixel document
 * is millions of draw calls a frame; the grid is finer than the screen at any zoom the app reaches
 * and costs a few thousand.
 */
private fun DrawScope.drawQuickMask(mask: ir.pixellab.core.paint.PixelSelection, viewport: Viewport) {
    val topLeft = viewport.toCanvas(Vec2(0f, 0f))
    val bottomRight = viewport.toCanvas(Vec2(size.width, size.height))
    val x0 = topLeft.x.toInt().coerceIn(0, mask.width - 1)
    val y0 = topLeft.y.toInt().coerceIn(0, mask.height - 1)
    val x1 = bottomRight.x.toInt().coerceIn(0, mask.width - 1)
    val y1 = bottomRight.y.toInt().coerceIn(0, mask.height - 1)
    if (x1 <= x0 || y1 <= y0) return

    // One cell per screen pixel or a little coarser, whichever is larger, so the cost is bounded by
    // the screen rather than by the document.
    val step = maxOf(1, ((x1 - x0) / MASK_CELLS), ((y1 - y0) / MASK_CELLS))
    val cell = viewport.toScreen(Vec2(step.toFloat(), step.toFloat())) - viewport.toScreen(Vec2(0f, 0f))
    val cellSize = androidx.compose.ui.geometry.Size(
        cell.x.coerceAtLeast(1f), cell.y.coerceAtLeast(1f),
    )

    var y = y0
    while (y <= y1) {
        var x = x0
        while (x <= x1) {
            val protectedness = 1f - mask[x, y] / 255f
            if (protectedness > 0.01f) {
                val at = viewport.toScreen(Vec2(x.toFloat(), y.toFloat()))
                drawRect(
                    MASK_TINT.copy(alpha = MASK_ALPHA * protectedness),
                    topLeft = Offset(at.x, at.y),
                    size = cellSize,
                )
            }
            x += step
        }
        y += step
    }
}

/**
 * The grid, drawn in two weights.
 *
 * A grid dense enough to place things against is too dense to read, and splitting the main lines
 * from the subdivisions is what makes both possible at once. The line count is bounded in
 * [ir.pixellab.core.canvas.GridSpec] rather than here, so a fine grid on a large canvas degrades to
 * its major lines instead of costing more to draw than the artwork.
 */
private fun DrawScope.drawGrid(state: EditorState) {
    val grid = state.grid
    if (!grid.visible) return
    val canvas = state.document.canvas
    val viewport = state.viewport

    for ((x, major) in grid.lines(canvas.width.toFloat())) {
        val from = viewport.toScreen(Vec2(x, 0f))
        val to = viewport.toScreen(Vec2(x, canvas.height.toFloat()))
        drawLine(
            color = UiColor.White.copy(alpha = if (major) GRID_MAJOR_ALPHA else GRID_MINOR_ALPHA),
            start = Offset(from.x, from.y),
            end = Offset(to.x, to.y),
            strokeWidth = 1f,
        )
    }
    for ((y, major) in grid.lines(canvas.height.toFloat())) {
        val from = viewport.toScreen(Vec2(0f, y))
        val to = viewport.toScreen(Vec2(canvas.width.toFloat(), y))
        drawLine(
            color = UiColor.White.copy(alpha = if (major) GRID_MAJOR_ALPHA else GRID_MINOR_ALPHA),
            start = Offset(from.x, from.y),
            end = Offset(to.x, to.y),
            strokeWidth = 1f,
        )
    }
}

/**
 * The region a platform will not cover, as a dimmed border rather than an outline.
 *
 * Dimming what is *outside* it rather than drawing a rectangle around it: the point is which part
 * of the design survives publication, and a thin line is easy to read as decoration and then
 * forget. The dim is subtle enough to judge colour through.
 */
private fun DrawScope.drawSafeZone(state: EditorState) {
    val zone = state.safeZone ?: return
    val canvas = state.document.canvas
    val inner = zone.rectFor(canvas.size)
    val viewport = state.viewport

    val outerTopLeft = viewport.toScreen(Vec2.ZERO)
    val outerBottomRight = viewport.toScreen(canvas.size)
    val innerTopLeft = viewport.toScreen(Vec2(inner.left, inner.top))
    val innerBottomRight = viewport.toScreen(Vec2(inner.right, inner.bottom))

    val shade = UiColor.Black.copy(alpha = SAFE_ZONE_ALPHA)
    // Four bands rather than a punched-out path: a path with an even-odd hole costs a layer save
    // on every frame, and four rectangles are exactly the same picture.
    drawRect(
        shade,
        topLeft = Offset(outerTopLeft.x, outerTopLeft.y),
        size = androidx.compose.ui.geometry.Size(
            outerBottomRight.x - outerTopLeft.x,
            innerTopLeft.y - outerTopLeft.y,
        ),
    )
    drawRect(
        shade,
        topLeft = Offset(outerTopLeft.x, innerBottomRight.y),
        size = androidx.compose.ui.geometry.Size(
            outerBottomRight.x - outerTopLeft.x,
            outerBottomRight.y - innerBottomRight.y,
        ),
    )
    drawRect(
        shade,
        topLeft = Offset(outerTopLeft.x, innerTopLeft.y),
        size = androidx.compose.ui.geometry.Size(
            innerTopLeft.x - outerTopLeft.x,
            innerBottomRight.y - innerTopLeft.y,
        ),
    )
    drawRect(
        shade,
        topLeft = Offset(innerBottomRight.x, innerTopLeft.y),
        size = androidx.compose.ui.geometry.Size(
            outerBottomRight.x - innerBottomRight.x,
            innerBottomRight.y - innerTopLeft.y,
        ),
    )
}

/**
 * The guides the user placed.
 *
 * Cyan rather than the snap guides' magenta, because the two mean different things and appear at
 * the same time: magenta is a transient "you are aligned to this right now", cyan is a standing
 * mark that stays after the finger lifts. A locked guide is dashed, so the reason it will not move
 * is visible before it is dragged at.
 */
private fun DrawScope.drawUserGuides(state: EditorState) {
    val canvas = state.document.canvas
    val viewport = state.viewport
    for (guide in state.document.guides) {
        val from: Vec2
        val to: Vec2
        if (guide.vertical) {
            from = viewport.toScreen(Vec2(guide.position, 0f))
            to = viewport.toScreen(Vec2(guide.position, canvas.height.toFloat()))
        } else {
            from = viewport.toScreen(Vec2(0f, guide.position))
            to = viewport.toScreen(Vec2(canvas.width.toFloat(), guide.position))
        }
        drawLine(
            color = UiColor(0xFF3FD8FF),
            start = Offset(from.x, from.y),
            end = Offset(to.x, to.y),
            strokeWidth = 1.5f,
            pathEffect = if (guide.locked) {
                PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
            } else {
                null
            },
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

/** Enough to read against artwork, faint enough not to compete with it. */
private const val GRID_MAJOR_ALPHA = 0.24f
private const val GRID_MINOR_ALPHA = 0.10f

/** Subtle enough to judge colour through, strong enough to see the boundary. */
private const val SAFE_ZONE_ALPHA = 0.34f

/**
 * Quick Mask's rubylith red — the colour of the physical masking film the mode is named after.
 *
 * Kept even though this app's accent is amber, because the wash has to be unmistakably *not* part of
 * the artwork, and a red at half opacity is the one tint no photograph is mistaken for.
 */
private val MASK_TINT = UiColor(0.85f, 0.15f, 0.2f)
private const val MASK_ALPHA = 0.5f

/** Cells across the visible canvas. Finer than the screen at any zoom, and bounded by it. */
private const val MASK_CELLS = 260
