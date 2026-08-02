package ir.pixellab.core.render

import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 3×3 affine map, stored column-major the way GLSL's `mat3` expects it.
 *
 * The composite and present passes both need to sample a texture that does *not* line up with what
 * they are drawing into — a layer sits somewhere on the canvas, and the canvas sits somewhere on
 * screen under a camera. Expressing that as one matrix the fragment shader applies to its own
 * coordinates keeps the single full-screen triangle: no vertex buffer, no second geometry path, and
 * the same shader serves both.
 */
@JvmInline
value class Affine(val values: FloatArray) {

    init {
        require(values.size == 9) { "an affine matrix needs nine values, got ${values.size}" }
    }

    /** Applies the map to a point, which is what the tests check rather than the raw numbers. */
    fun map(point: Vec2) = Vec2(
        values[0] * point.x + values[3] * point.y + values[6],
        values[1] * point.x + values[4] * point.y + values[7],
    )

    operator fun times(other: Affine): Affine {
        val a = values
        val b = other.values
        val out = FloatArray(9)
        for (column in 0..2) {
            for (row in 0..2) {
                out[column * 3 + row] =
                    a[row] * b[column * 3] +
                    a[3 + row] * b[column * 3 + 1] +
                    a[6 + row] * b[column * 3 + 2]
            }
        }
        return Affine(out)
    }

    companion object {
        val IDENTITY = Affine(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        fun of(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float) =
            Affine(floatArrayOf(a, b, 0f, c, d, 0f, tx, ty, 1f))

        fun translate(x: Float, y: Float) = of(1f, 0f, 0f, 1f, x, y)

        fun scale(x: Float, y: Float) = of(x, 0f, 0f, y, 0f, 0f)

        /** Clockwise under a downward y axis, matching every other rotation in the editor. */
        fun rotate(degrees: Float): Affine {
            val r = Math.toRadians(degrees.toDouble())
            return of(cos(r).toFloat(), sin(r).toFloat(), -sin(r).toFloat(), cos(r).toFloat(), 0f, 0f)
        }
    }
}

/**
 * Inverts an affine map.
 *
 * Every mapping here is built forwards — layer to canvas, canvas to screen — because that is how the
 * model expresses it, but a fragment shader works backwards: it knows where it is in the *target*
 * and has to find the matching point in the source. Building the inverse by hand at each call site
 * is where sign errors live.
 */
fun Affine.inverse(): Affine {
    val m = values
    val determinant = m[0] * m[4] - m[3] * m[1]
    // A collapsed layer — scaled to zero on an axis — has no inverse. Returning identity draws it
    // in the wrong place; returning a degenerate map draws nothing, which is what a zero-width
    // layer should look like.
    if (kotlin.math.abs(determinant) < 1e-9f) return Affine(FloatArray(9))
    val inverse = 1f / determinant
    val a = m[4] * inverse
    val b = -m[1] * inverse
    val c = -m[3] * inverse
    val d = m[0] * inverse
    return Affine.of(a, b, c, d, -(a * m[6] + c * m[7]), -(b * m[6] + d * m[7]))
}

/**
 * The mappings the compositor needs.
 *
 * Kept in pure Kotlin, and expressed as "where does this target pixel come from", because that is
 * the direction the shader asks in and the direction that is easy to get backwards.
 */
object Compositing {

    /**
     * Maps a point in the layer's own coordinates onto the canvas.
     *
     * The same arithmetic as `Handles.localToCanvas`, as a matrix: scale and rotate about the
     * anchor, then translate.
     */
    fun layerToCanvas(bounds: Rect, transform: Transform): Affine {
        val anchor = Vec2(
            bounds.left + transform.anchor.x * bounds.width,
            bounds.top + transform.anchor.y * bounds.height,
        )
        return Affine.translate(anchor.x + transform.translation.x, anchor.y + transform.translation.y) *
            Affine.rotate(transform.rotation) *
            Affine.scale(transform.scale.x, transform.scale.y) *
            Affine.translate(-anchor.x, -anchor.y)
    }

    /**
     * Maps canvas UV to the layer texture's UV, which is what the composite shader samples with.
     *
     * @param textureBounds the layer texture's rectangle in layer coordinates, already grown by the
     *   effects' bleed — so the shadow that reaches past the shape is inside the texture too
     */
    fun canvasUvToLayerUv(
        canvasSize: Vec2,
        textureBounds: Rect,
        transform: Transform,
    ): Affine {
        val canvasUvToCanvas = Affine.scale(canvasSize.x, canvasSize.y)
        val canvasToLayer = layerToCanvas(textureBounds, transform).inverse()
        val layerToTextureUv = Affine.scale(1f / textureBounds.width, 1f / textureBounds.height) *
            Affine.translate(-textureBounds.left, -textureBounds.top)
        return layerToTextureUv * canvasToLayer * canvasUvToCanvas
    }

    /**
     * Maps screen UV to canvas UV, for the pass that puts the finished canvas on screen.
     *
     * This is where the camera finally applies. Rendering every layer through the camera instead
     * would re-run the whole effect stack on every pan, which is exactly what makes a canvas lag
     * behind a finger.
     */
    fun screenUvToCanvasUv(screenSize: Vec2, canvasSize: Vec2, offset: Vec2, zoom: Float, rotation: Float): Affine {
        val screenUvToScreen = Affine.scale(screenSize.x, screenSize.y)
        val screenToCanvas = Affine.scale(1f / zoom, 1f / zoom) *
            Affine.rotate(-rotation) *
            Affine.translate(-offset.x, -offset.y)
        val canvasToUv = Affine.scale(1f / canvasSize.x, 1f / canvasSize.y)
        return canvasToUv * screenToCanvas * screenUvToScreen
    }
}
