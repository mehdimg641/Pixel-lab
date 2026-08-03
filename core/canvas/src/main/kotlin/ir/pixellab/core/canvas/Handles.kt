package ir.pixellab.core.canvas

import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign

/** The grab points around a selection. [BODY] is the interior, which moves the layer. */
enum class Handle {
    TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, ROTATE, BODY;

    val isCorner: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_RIGHT || this == BOTTOM_LEFT

    val isEdge: Boolean get() = this == TOP || this == RIGHT || this == BOTTOM || this == LEFT

    /**
     * Where this handle sits in the layer's own box, as a fraction.
     *
     * (0,0) is the top left corner, (1,1) the bottom right. [ROTATE] and [BODY] both report the
     * centre; neither is positioned from this.
     */
    val unitPosition: Vec2
        get() = when (this) {
            TOP_LEFT -> Vec2(0f, 0f)
            TOP -> Vec2(0.5f, 0f)
            TOP_RIGHT -> Vec2(1f, 0f)
            RIGHT -> Vec2(1f, 0.5f)
            BOTTOM_RIGHT -> Vec2(1f, 1f)
            BOTTOM -> Vec2(0.5f, 1f)
            BOTTOM_LEFT -> Vec2(0f, 1f)
            LEFT -> Vec2(0f, 0.5f)
            ROTATE, BODY -> Vec2(0.5f, 0.5f)
        }

    /** The handle diagonally or laterally opposite, which stays fixed while this one is dragged. */
    val opposite: Handle
        get() = when (this) {
            TOP_LEFT -> BOTTOM_RIGHT
            TOP -> BOTTOM
            TOP_RIGHT -> BOTTOM_LEFT
            RIGHT -> LEFT
            BOTTOM_RIGHT -> TOP_LEFT
            BOTTOM -> TOP
            BOTTOM_LEFT -> TOP_RIGHT
            LEFT -> RIGHT
            ROTATE, BODY -> this
        }
}

data class HandleConfig(
    /**
     * Radius of the touch target, in screen pixels.
     *
     * Sized to the platform's 48dp minimum rather than to the dot that is drawn. A handle drawn at
     * 12dp and hit-tested at 12dp is the reason resize on a phone feels like it misses.
     */
    val touchRadius: Float = 24f,
    /** How far outside the box the rotation handle sits, in screen pixels. */
    val rotateDistance: Float = 44f,
    /** Rotation snaps to a multiple of this when within [rotationSnapWindow]. */
    val rotationSnapStep: Float = 15f,
    val rotationSnapWindow: Float = 4f,
    /** A layer may not be resized below this, in canvas units, or it becomes impossible to grab. */
    val minimumSize: Float = 4f,
)

/**
 * A selection's handles in screen space.
 *
 * Screen space, not canvas space: handles keep a constant size and a constant reach as the user
 * zooms, which is what keeps them grabbable at 5% and from swallowing the artwork at 3200%.
 */
data class HandleLayout(
    val positions: Map<Handle, Vec2>,
    val corners: List<Vec2>,
    val rotation: Float,
) {
    /**
     * The handle under [screenPoint], or null.
     *
     * Order matters and is not the declaration order: corners win over edges because they overlap at
     * small sizes and a corner is the more useful of the two, and the body is tested last because it
     * covers everything.
     */
    fun hitTest(screenPoint: Vec2, config: HandleConfig = HandleConfig()): Handle? {
        val ranked = Handle.entries.filter { it.isCorner } +
            Handle.entries.filter { it.isEdge } +
            listOf(Handle.ROTATE)
        val nearest = ranked
            .mapNotNull { handle -> positions[handle]?.let { handle to (screenPoint - it).length } }
            .filter { it.second <= config.touchRadius }
            .minByOrNull { it.second }
        if (nearest != null) return nearest.first
        return if (containsPoint(screenPoint)) Handle.BODY else null
    }

    /** Whether a screen point falls inside the rotated selection box. */
    fun containsPoint(screenPoint: Vec2): Boolean {
        // Winding test against the four edges: the box is rotated, so an axis-aligned bounds check
        // would select a layer the user did not touch.
        var previousSide = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val edge = b - a
            val side = edge.x * (screenPoint.y - a.y) - edge.y * (screenPoint.x - a.x)
            if (side != 0f) {
                if (previousSide != 0f && sign(side) != sign(previousSide)) return false
                previousSide = side
            }
        }
        return true
    }
}

/**
 * Positions, transforms and hit-tests the handles around a layer.
 *
 * The layer's placement is a [Transform] over local [Rect] bounds, so all of this is expressed as:
 * map local to canvas, map canvas to screen, and for a drag, invert both.
 */
object Handles {

    /**
     * Maps a point in the layer's own coordinates onto the canvas.
     *
     * Scale, then skew, then rotate, about the anchor. The order matters and is the same one
     * `Compositing.layerToCanvas` builds its matrix in — these two are independent implementations
     * of one placement, and a disagreement between them shows as selection handles that no longer
     * sit on the artwork they belong to.
     */
    fun localToCanvas(local: Vec2, bounds: Rect, transform: Transform): Vec2 {
        // A four-corner warp is projective and cannot be written as a sequence of vector
        // operations, so the moment one is present this defers to the matrix the compositor
        // builds. That is not a fallback — it is the same map, and using it here is what
        // guarantees the handles land on the pixels.
        if (transform.perspective != null) {
            return ir.pixellab.core.model.Affine.warpAware(bounds, transform).map(local)
        }
        val anchor = anchorOf(bounds, transform)
        val scaled = (local - anchor) * transform.scale
        return sheared(scaled, transform.skew).rotated(transform.rotation) + anchor + transform.translation
    }

    fun canvasToLocal(canvas: Vec2, bounds: Rect, transform: Transform): Vec2 {
        if (transform.perspective != null) {
            return ir.pixellab.core.model.Affine.warpAware(bounds, transform).inverse().map(canvas)
        }
        val anchor = anchorOf(bounds, transform)
        val unrotated = (canvas - transform.translation - anchor).rotated(-transform.rotation)
        val unsheared = unsheared(unrotated, transform.skew)
        return Vec2(
            anchor.x + unsheared.x / nonZero(transform.scale.x),
            anchor.y + unsheared.y / nonZero(transform.scale.y),
        )
    }

    /**
     * Slants a vector along the layer's own axes — Photoshop's Skew, in degrees.
     *
     * Degrees rather than a ratio because that is what a designer reads off a reference: an italic
     * is "twelve degrees", never "point two one".
     */
    private fun sheared(v: Vec2, skew: Vec2): Vec2 {
        if (skew.x == 0f && skew.y == 0f) return v
        val tx = tanDegrees(skew.x)
        val ty = tanDegrees(skew.y)
        return Vec2(v.x + tx * v.y, ty * v.x + v.y)
    }

    private fun unsheared(v: Vec2, skew: Vec2): Vec2 {
        if (skew.x == 0f && skew.y == 0f) return v
        val tx = tanDegrees(skew.x)
        val ty = tanDegrees(skew.y)
        // The determinant of a shear is 1 - tx·ty, and it reaches zero when the two slants make the
        // axes parallel. Clamping each to 85 degrees keeps it clear of that, and this guard covers
        // the corner where both are near the limit at once.
        val determinant = nonZero(1f - tx * ty)
        return Vec2((v.x - tx * v.y) / determinant, (v.y - ty * v.x) / determinant)
    }

    private fun tanDegrees(degrees: Float) =
        kotlin.math.tan(Math.toRadians(degrees.coerceIn(-SKEW_LIMIT, SKEW_LIMIT).toDouble())).toFloat()

    /** Photoshop's own slider stops here, and past it the tangent runs away. */
    const val SKEW_LIMIT = 85f

    fun layout(
        bounds: Rect,
        transform: Transform,
        viewport: Viewport,
        config: HandleConfig = HandleConfig(),
    ): HandleLayout {
        val screenOf = { unit: Vec2 ->
            viewport.toScreen(
                localToCanvas(
                    Vec2(bounds.left + unit.x * bounds.width, bounds.top + unit.y * bounds.height),
                    bounds,
                    transform,
                ),
            )
        }
        val positions = LinkedHashMap<Handle, Vec2>()
        for (handle in Handle.entries) {
            if (handle == Handle.ROTATE || handle == Handle.BODY) continue
            positions[handle] = screenOf(handle.unitPosition)
        }

        // The rotation handle hangs off the top edge along the box's own normal, so it follows the
        // layer round instead of always sitting above the screen.
        val top = positions.getValue(Handle.TOP)
        val bottom = positions.getValue(Handle.BOTTOM)
        val outward = top - bottom
        val normal = if (outward.length > 0.001f) outward / outward.length else Vec2(0f, -1f)
        positions[Handle.ROTATE] = top + normal * config.rotateDistance
        positions[Handle.BODY] = screenOf(Vec2(0.5f, 0.5f))

        val corners = listOf(Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT, Handle.BOTTOM_LEFT)
            .map(positions::getValue)
        val screenRotation = (positions.getValue(Handle.RIGHT) - positions.getValue(Handle.LEFT)).angle
        return HandleLayout(positions, corners, screenRotation)
    }

    /**
     * Applies a resize drag and returns the new transform.
     *
     * The handle opposite the one being dragged is held fixed. Computing the new scale and letting
     * the translation stay put is the obvious implementation and it makes the layer grow from its
     * centre no matter which handle was grabbed — so dragging the right edge visibly moves the left
     * one, which is wrong everywhere except when [fromCentre] is on.
     *
     * @param dragCanvas total movement since the drag began, in canvas units
     * @param lockAspect keeps the original proportions; corners honour it, edges cannot
     * @param fromCentre grows symmetrically about the anchor instead of about the opposite handle
     */
    fun resize(
        handle: Handle,
        bounds: Rect,
        start: Transform,
        dragCanvas: Vec2,
        lockAspect: Boolean = false,
        fromCentre: Boolean = false,
        config: HandleConfig = HandleConfig(),
    ): Transform {
        if (handle == Handle.BODY) return start.copy(translation = start.translation + dragCanvas)
        if (handle == Handle.ROTATE || bounds.width <= 0f || bounds.height <= 0f) return start

        // In the layer's own frame the drag is axis aligned again, which is what makes an edge
        // handle move along the edge it belongs to rather than along the screen.
        val local = dragCanvas.rotated(-start.rotation)
        val unit = handle.unitPosition
        val dirX = (unit.x - 0.5f) * 2f
        val dirY = (unit.y - 0.5f) * 2f

        val grow = if (fromCentre) 2f else 1f
        var scaleX = start.scale.x + (if (dirX != 0f) dirX * local.x * grow / bounds.width else 0f)
        var scaleY = start.scale.y + (if (dirY != 0f) dirY * local.y * grow / bounds.height else 0f)

        if (lockAspect && handle.isCorner) {
            // Follow whichever axis the finger pushed further, so the box tracks the finger on the
            // dominant direction instead of snapping between the two.
            val ratio = if (abs(local.x) * bounds.height >= abs(local.y) * bounds.width) {
                safeRatio(scaleX, start.scale.x)
            } else {
                safeRatio(scaleY, start.scale.y)
            }
            scaleX = start.scale.x * ratio
            scaleY = start.scale.y * ratio
        }

        scaleX = clampScale(scaleX, bounds.width, config.minimumSize)
        scaleY = clampScale(scaleY, bounds.height, config.minimumSize)
        val resized = start.copy(scale = Vec2(scaleX, scaleY))
        if (fromCentre) return resized

        // Whatever the scale did, put the fixed point back where it was.
        val fixed = handle.opposite.unitPosition
        val fixedLocal = Vec2(
            bounds.left + fixed.x * bounds.width,
            bounds.top + fixed.y * bounds.height,
        )
        val before = localToCanvas(fixedLocal, bounds, start)
        val after = localToCanvas(fixedLocal, bounds, resized)
        return resized.copy(translation = resized.translation + (before - after))
    }

    /**
     * Applies a rotation drag.
     *
     * @param startCanvas where the finger first grabbed the rotation handle, in canvas units
     * @param currentCanvas where it is now
     * @param snap snaps to [HandleConfig.rotationSnapStep] near a multiple of it
     */
    fun rotate(
        bounds: Rect,
        start: Transform,
        startCanvas: Vec2,
        currentCanvas: Vec2,
        snap: Boolean = true,
        config: HandleConfig = HandleConfig(),
    ): Transform {
        val pivot = localToCanvas(anchorOf(bounds, start), bounds, start)
        val from = startCanvas - pivot
        val to = currentCanvas - pivot
        // Too close to the pivot the angle is noise, and the layer would spin on a pixel of jitter.
        if (from.length < 1f || to.length < 1f) return start

        val turned = start.rotation + deltaDegrees(from.angle, to.angle)
        val snapped = if (snap) snapAngle(turned, config) else turned
        return start.copy(rotation = normaliseDegrees(snapped))
    }

    /** Nearest multiple of the step, but only when already close to one. */
    fun snapAngle(degrees: Float, config: HandleConfig = HandleConfig()): Float {
        if (config.rotationSnapStep <= 0f) return degrees
        val nearest = round(degrees / config.rotationSnapStep) * config.rotationSnapStep
        return if (abs(degrees - nearest) <= config.rotationSnapWindow) nearest else degrees
    }

    /** The layer's axis-aligned bounds on the canvas, which is what snapping and bleed both need. */
    fun canvasBounds(bounds: Rect, transform: Transform): Rect {
        val corners = listOf(
            Vec2(bounds.left, bounds.top), Vec2(bounds.right, bounds.top),
            Vec2(bounds.right, bounds.bottom), Vec2(bounds.left, bounds.bottom),
        ).map { localToCanvas(it, bounds, transform) }
        return Rect(
            corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y },
        )
    }

    private fun anchorOf(bounds: Rect, transform: Transform) = Vec2(
        bounds.left + transform.anchor.x * bounds.width,
        bounds.top + transform.anchor.y * bounds.height,
    )

    /**
     * Keeps a scale from crossing zero.
     *
     * Dragging a handle past the opposite side is a legitimate flip, so the sign is preserved; what
     * is not allowed is landing on zero, which collapses the layer to a line no handle can grab.
     */
    private fun clampScale(scale: Float, extent: Float, minimum: Float): Float {
        val floor = if (extent > 0f) minimum / extent else minimum
        return if (abs(scale) < floor) floor * (if (scale < 0f) -1f else 1f) else scale
    }

    private fun safeRatio(value: Float, base: Float) = if (abs(base) < 1e-5f) 1f else value / base

    private fun nonZero(v: Float) = if (abs(v) < 1e-5f) 1e-5f else v
}
