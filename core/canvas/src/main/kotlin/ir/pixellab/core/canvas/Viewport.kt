package ir.pixellab.core.canvas

import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * How the canvas sits on the screen.
 *
 * Kept as pan, zoom and rotation rather than a matrix for the same reason [ir.pixellab.core.model.Transform]
 * is: the zoom percentage has to be shown, typed into, and restored across a session, and a
 * collapsed matrix cannot be decomposed back without ambiguity.
 *
 * A canvas point maps to the screen as `rotate(canvas * zoom, rotation) + offset`. All rotation in
 * the editor is clockwise under a downward y axis; see [Vec2.rotated].
 */
data class Viewport(
    val offset: Vec2 = Vec2.ZERO,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val screenSize: Vec2 = Vec2.ZERO,
) {
    fun toScreen(canvasPoint: Vec2): Vec2 = (canvasPoint * zoom).rotated(rotation) + offset

    fun toCanvas(screenPoint: Vec2): Vec2 = (screenPoint - offset).rotated(-rotation) / zoom

    /** Screen distance for a canvas distance. Handle sizes and snap tolerances need the inverse. */
    fun toScreenDistance(canvasDistance: Float): Float = canvasDistance * zoom

    fun toCanvasDistance(screenDistance: Float): Float = screenDistance / zoom

    fun panBy(screenDelta: Vec2): Viewport = copy(offset = offset + screenDelta)

    /**
     * Scales about a screen point, leaving the canvas point under it fixed.
     *
     * Zooming about the screen centre is the obvious implementation and it is why pinch-zoom in
     * weaker editors slides the artwork out from under the fingers.
     */
    fun zoomBy(factor: Float, pivot: Vec2): Viewport {
        val clamped = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (clamped == zoom) return this
        val anchored = toCanvas(pivot)
        val scaled = copy(zoom = clamped)
        return scaled.copy(offset = scaled.offset + (pivot - scaled.toScreen(anchored)))
    }

    /** Rotates about a screen point, leaving the canvas point under it fixed. */
    fun rotateBy(degrees: Float, pivot: Vec2): Viewport {
        if (degrees == 0f) return this
        val anchored = toCanvas(pivot)
        val turned = copy(rotation = normaliseDegrees(rotation + degrees))
        return turned.copy(offset = offset + (pivot - turned.toScreen(anchored)))
    }

    /**
     * Applies a two-finger gesture as one operation.
     *
     * Pan, scale and rotation have to be composed about the same pivot in a single step. Applying
     * them one after another about separately recomputed pivots is the standard mistake and it makes
     * the artwork creep away from the fingers over a long gesture.
     */
    fun transformBy(pan: Vec2, scaleFactor: Float, rotationDegrees: Float, pivot: Vec2): Viewport =
        zoomBy(scaleFactor, pivot).rotateBy(rotationDegrees, pivot).panBy(pan)

    /** Centres [canvasSize] on screen at the largest zoom that leaves [padding] screen pixels around it. */
    /**
     * Sizes and centres the artboard in the part of the screen the user can actually see.
     *
     * [insetTop] and [insetBottom] are the fixed bars — the history strip above, the ribbon and dock
     * below. Fitting to the whole screen instead, which is what this did, centres the artboard
     * behind them: on a phone the bars are around 200dp of a 900dp screen, so the artwork sits low,
     * its bottom edge is under the ribbon, and the whole document looks off-centre and too large.
     * Nothing about it is wrong except that the program measured a rectangle nobody is looking at.
     */
    fun fit(
        canvasSize: Vec2,
        padding: Float = 0f,
        insetTop: Float = 0f,
        insetBottom: Float = 0f,
    ): Viewport {
        if (screenSize.x <= 0f || screenSize.y <= 0f || canvasSize.x <= 0f || canvasSize.y <= 0f) return this
        // Insets that would swallow the screen are ignored rather than clamped to a sliver: a
        // one-pixel-tall working area is not a better answer than the whole screen.
        val visibleHeight = screenSize.y - insetTop - insetBottom
        val top = if (visibleHeight > MIN_VISIBLE) insetTop else 0f
        val height = if (visibleHeight > MIN_VISIBLE) visibleHeight else screenSize.y
        val available = Vec2(
            max(1f, screenSize.x - padding * 2f),
            max(1f, height - padding * 2f),
        )
        val scale = min(available.x / canvasSize.x, available.y / canvasSize.y).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val centred = Viewport(zoom = scale, rotation = 0f, screenSize = screenSize)
        val canvasCentre = centred.toScreen(canvasSize * 0.5f)
        val target = Vec2(screenSize.x * 0.5f, top + height * 0.5f)
        return centred.copy(offset = target - canvasCentre)
    }

    /** Zoom expressed the way it is shown to the user, where 100 means one canvas pixel per screen pixel. */
    val zoomPercent: Float get() = zoom * 100f

    /**
     * Pans, and shrinks only if it must, so [canvasRect] sits inside the region left visible when a
     * sheet covers the bottom [obstructedBottom] pixels of the screen.
     *
     * This is the rule that a parameter sheet must never cover what it edits. Panning alone is tried
     * first because changing the zoom mid-edit re-renders every layer and loses the user's framing;
     * the zoom is only reduced when the layer genuinely cannot fit in what is left.
     */
    fun revealing(
        canvasRect: Rect,
        obstructedBottom: Float,
        obstructedTop: Float = 0f,
        padding: Float = 16f,
    ): Viewport {
        val visibleHeight = screenSize.y - obstructedTop - obstructedBottom - padding * 2f
        val visibleWidth = screenSize.x - padding * 2f
        if (visibleHeight <= 0f || visibleWidth <= 0f) return this

        val corners = listOf(
            Vec2(canvasRect.left, canvasRect.top), Vec2(canvasRect.right, canvasRect.top),
            Vec2(canvasRect.right, canvasRect.bottom), Vec2(canvasRect.left, canvasRect.bottom),
        ).map(::toScreen)
        val bounds = Rect(
            corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y },
        )

        val shrink = min(1f, min(visibleWidth / max(bounds.width, 1f), visibleHeight / max(bounds.height, 1f)))
        val screenCentre = Vec2(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
        val shrunk = if (shrink < 1f) zoomBy(shrink, screenCentre) else this

        val moved = listOf(
            Vec2(canvasRect.left, canvasRect.top), Vec2(canvasRect.right, canvasRect.top),
            Vec2(canvasRect.right, canvasRect.bottom), Vec2(canvasRect.left, canvasRect.bottom),
        ).map(shrunk::toScreen)
        val target = Vec2(
            (moved.minOf { it.x } + moved.maxOf { it.x }) / 2f,
            (moved.minOf { it.y } + moved.maxOf { it.y }) / 2f,
        )
        val destination = Vec2(
            screenSize.x / 2f,
            obstructedTop + (screenSize.y - obstructedTop - obstructedBottom) / 2f,
        )
        return shrunk.panBy(destination - target)
    }

    /** Screen-space bounds of the canvas, for drawing its border and the checkerboard behind it. */
    fun screenBounds(canvasSize: Vec2): Rect {
        val corners = listOf(
            Vec2.ZERO, Vec2(canvasSize.x, 0f), canvasSize, Vec2(0f, canvasSize.y),
        ).map(::toScreen)
        return Rect(
            corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y },
        )
    }

    companion object {
        /**
         * Zoom limits.
         *
         * The ceiling matches Photoshop's 3200%, which is what pixel-level mask cleanup needs. The
         * floor is deliberately far below fit-to-screen so a large canvas can be surveyed whole.
         */
        const val MIN_ZOOM = 0.02f
        const val MAX_ZOOM = 32f

        /** Below this the chrome is claiming the whole screen, which is a caller's mistake to ignore. */
        const val MIN_VISIBLE = 64f
    }
}

/** Folds an angle into -180..180 so a long rotation gesture cannot accumulate to 3600 degrees. */
fun normaliseDegrees(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** Shortest signed rotation from [from] to [to], which is what a rotation gesture accumulates. */
fun deltaDegrees(from: Float, to: Float): Float = normaliseDegrees(to - from)
