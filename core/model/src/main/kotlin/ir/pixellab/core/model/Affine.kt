package ir.pixellab.core.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A 3×3 map, stored column-major the way GLSL's `mat3` expects it.
 *
 * The composite and present passes both need to sample a texture that does *not* line up with what
 * they are drawing into — a layer sits somewhere on the canvas, and the canvas sits somewhere on
 * screen under a camera. Expressing that as one matrix the fragment shader applies to its own
 * coordinates keeps the single full-screen triangle: no vertex buffer, no second geometry path, and
 * the same shader serves both.
 *
 * **Projective, not merely affine.** The bottom row is carried and [map] divides by the resulting
 * *w*. For every transform built from a translation, a rotation, a scale or a shear that row is
 * `(0, 0, 1)` and the divide is by one, so nothing changes; a four-corner warp is the case where it
 * is not, and it is the only way to express one. A matrix class that quietly dropped the row would
 * make perspective unrepresentable rather than merely unimplemented.
 *
 * This lives in the model rather than beside either renderer for the reason [Ramp] does: the
 * compositor and the gesture layer are sibling modules that both need it, and two implementations
 * of one placement drift — the symptom being selection handles that no longer sit on the artwork
 * they belong to.
 */
@JvmInline
value class Affine(val values: FloatArray) {

    init {
        require(values.size == 9) { "a 3x3 matrix needs nine values, got ${values.size}" }
    }

    /**
     * Applies the map to a point, dividing through by *w*.
     *
     * The divide is what makes this projective. For an affine matrix *w* is exactly one and the
     * arithmetic is unchanged; for a warp it is the thing that makes the far edge of a plane
     * smaller than the near one.
     */
    fun map(point: Vec2): Vec2 {
        val x = values[0] * point.x + values[3] * point.y + values[6]
        val y = values[1] * point.x + values[4] * point.y + values[7]
        val w = values[2] * point.x + values[5] * point.y + values[8]
        // A point on the horizon has no image. Returning it unscaled keeps the arithmetic finite
        // rather than handing NaN to whatever asked, and a caller drawing it will place it far
        // outside the canvas, which is where a point at infinity belongs.
        if (abs(w) < EPSILON) return Vec2(x, y)
        return Vec2(x / w, y / w)
    }

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

    /**
     * Inverts the map.
     *
     * Every mapping is built forwards — layer to canvas, canvas to screen — because that is how the
     * model expresses it, but a fragment shader works backwards: it knows where it is in the
     * *target* and has to find the matching point in the source. Building the inverse by hand at
     * each call site is where sign errors live.
     *
     * The full 3×3 adjugate rather than the affine shortcut, because the shortcut inverts only the
     * top-left 2×2 block and a warp's information is entirely in the row it ignores.
     */
    fun inverse(): Affine {
        val m = values
        // Column-major, so m[column * 3 + row].
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]

        val cofactor0 = e * i - f * h
        val cofactor1 = f * g - d * i
        val cofactor2 = d * h - e * g
        val determinant = a * cofactor0 + b * cofactor1 + c * cofactor2

        // A collapsed layer — scaled to zero on an axis, or warped onto a line — has no inverse.
        // Returning identity would draw it in the wrong place; a degenerate map draws nothing,
        // which is what a zero-width layer should look like.
        if (abs(determinant) < EPSILON) return Affine(FloatArray(9))

        val k = 1f / determinant
        return Affine(
            floatArrayOf(
                cofactor0 * k, (c * h - b * i) * k, (b * f - c * e) * k,
                cofactor1 * k, (a * i - c * g) * k, (c * d - a * f) * k,
                cofactor2 * k, (b * g - a * h) * k, (a * e - b * d) * k,
            ),
        )
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

        /**
         * Slants the axes — Photoshop's Skew, in degrees.
         *
         * Degrees rather than a raw ratio because that is what the panel shows and what a designer
         * reads off a reference: an italic is "twelve degrees", never "point two one". The tangent
         * converts one to the other, and it is the reason the control has to stop short of a right
         * angle — at ninety the axes are parallel, the determinant is zero, and the layer collapses
         * to a line it can never be dragged back from.
         */
        fun shear(xDegrees: Float, yDegrees: Float): Affine {
            val sx = tan(Math.toRadians(xDegrees.coerceIn(-SHEAR_LIMIT, SHEAR_LIMIT).toDouble()))
            val sy = tan(Math.toRadians(yDegrees.coerceIn(-SHEAR_LIMIT, SHEAR_LIMIT).toDouble()))
            return of(1f, sy.toFloat(), sx.toFloat(), 1f, 0f, 0f)
        }

        /**
         * The map taking the unit square's corners to four arbitrary points — Photoshop's Distort
         * and Perspective, and the only transform here that is not affine.
         *
         * Solved rather than iterated. The unit square is the easy case: with the corners named
         * (0,0), (1,0), (1,1), (0,1), the eight unknowns collapse to a 2×2 system in the two
         * bottom-row terms, and the rest follow by substitution. A general four-point solve is a
         * 8×8 elimination; this is a dozen multiplications, which matters because a corner drag
         * rebuilds it on every frame.
         *
         * The order is the model's own: top-left, top-right, bottom-right, bottom-left, going round
         * rather than in reading order. Going round is what makes a *crossed* quadrilateral
         * expressible — dragging one corner past its neighbour folds the layer, which Photoshop
         * allows and which reading order cannot describe.
         */
        fun corners(topLeft: Vec2, topRight: Vec2, bottomRight: Vec2, bottomLeft: Vec2): Affine {
            val dx1 = topRight.x - bottomRight.x
            val dx2 = bottomLeft.x - bottomRight.x
            val dy1 = topRight.y - bottomRight.y
            val dy2 = bottomLeft.y - bottomRight.y
            val sx = topLeft.x - topRight.x + bottomRight.x - bottomLeft.x
            val sy = topLeft.y - topRight.y + bottomRight.y - bottomLeft.y

            val denominator = dx1 * dy2 - dx2 * dy1
            // The two diagonals of a parallelogram meet at its centre, so the system is singular
            // exactly when the warp is still affine — which is the common case, not an error.
            if (abs(denominator) < EPSILON) {
                return Affine(
                    floatArrayOf(
                        topRight.x - topLeft.x, topRight.y - topLeft.y, 0f,
                        bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y, 0f,
                        topLeft.x, topLeft.y, 1f,
                    ),
                )
            }

            val g = (sx * dy2 - dx2 * sy) / denominator
            val h = (dx1 * sy - sx * dy1) / denominator
            return Affine(
                floatArrayOf(
                    topRight.x - topLeft.x + g * topRight.x, topRight.y - topLeft.y + g * topRight.y, g,
                    bottomLeft.x - topLeft.x + h * bottomLeft.x, bottomLeft.y - topLeft.y + h * bottomLeft.y, h,
                    topLeft.x, topLeft.y, 1f,
                ),
            )
        }

        /**
         * A layer's whole placement as one matrix: scale, then skew, then rotate about the anchor,
         * then translate, and the four-corner warp after all of it.
         *
         * The order is not interchangeable. Skewing after rotating slants along the *screen's* axes
         * rather than the layer's, so a rotated layer's handle would drag it in a direction
         * unrelated to the edge being pulled; scaling last would let a non-uniform scale change the
         * skew angle, since a shear and a scale do not commute. And the warp comes last because its
         * corners are stated in canvas coordinates — they are where the user put them — so folding
         * it in earlier would have a rotation spin the warp along with the layer.
         *
         * Both the compositor and the gesture layer call this. That is the entire point of it
         * living here: two implementations of one placement drift, and the symptom is selection
         * handles that no longer sit on the artwork they belong to.
         */
        fun warpAware(bounds: Rect, transform: Transform): Affine {
            val anchor = Vec2(
                bounds.left + transform.anchor.x * bounds.width,
                bounds.top + transform.anchor.y * bounds.height,
            )
            val affine = translate(anchor.x + transform.translation.x, anchor.y + transform.translation.y) *
                rotate(transform.rotation) *
                shear(transform.skew.x, transform.skew.y) *
                scale(transform.scale.x, transform.scale.y) *
                translate(-anchor.x, -anchor.y)

            val warp = transform.perspective ?: return affine
            val toUnitSquare = scale(1f / nonZero(bounds.width), 1f / nonZero(bounds.height)) *
                translate(-bounds.left, -bounds.top)
            return affine * corners(warp.topLeft, warp.topRight, warp.bottomRight, warp.bottomLeft) *
                toUnitSquare
        }

        private fun nonZero(v: Float) = if (abs(v) < 1e-5f) 1e-5f else v

        /** Photoshop's own slider stops here, and past it the tangent runs away. */
        const val SHEAR_LIMIT = 85f

        /** Below this a determinant is noise rather than a number. */
        private const val EPSILON = 1e-9f
    }
}
