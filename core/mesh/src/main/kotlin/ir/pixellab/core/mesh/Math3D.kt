package ir.pixellab.core.mesh

import ir.pixellab.core.model.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Vector arithmetic, kept as extensions so the model's [Vec3] stays a plain serialisable value. */
operator fun Vec3.plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

operator fun Vec3.minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

operator fun Vec3.times(s: Float) = Vec3(x * s, y * s, z * s)

infix fun Vec3.dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

infix fun Vec3.cross(o: Vec3) = Vec3(
    y * o.z - z * o.y,
    z * o.x - x * o.z,
    x * o.y - y * o.x,
)

val Vec3.length: Float get() = sqrt(this dot this)

/**
 * Unit length, or zero if there is no direction to preserve.
 *
 * Returning zero rather than dividing is not defensive padding: a degenerate triangle in a
 * tessellated glyph has a zero-length normal, and a NaN there propagates through the whole shading
 * pass and comes out as a black hole in the render that is impossible to trace back.
 */
fun Vec3.normalised(): Vec3 {
    val l = length
    return if (l < EPSILON) Vec3.ZERO else Vec3(x / l, y / l, z / l)
}

/**
 * A 4×4 matrix in **column-major** order, the layout OpenGL expects.
 *
 * Column-major is chosen for one reason: the array can be handed to `glUniformMatrix4fv` without
 * transposing, and a transpose that is applied in one place and forgotten in another is the classic
 * way a 3D scene ends up mirrored in a way nobody can find.
 */
@JvmInline
value class Mat4(val m: FloatArray) {

    init {
        require(m.size == SIZE) { "a 4x4 matrix has $SIZE values, got ${m.size}" }
    }

    operator fun times(o: Mat4): Mat4 {
        val out = FloatArray(SIZE)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += m[k * 4 + row] * o.m[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return Mat4(out)
    }

    /** Transforms a point — the translation column applies. */
    fun transform(v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14],
    )

    /** Transforms a direction — translation is skipped, which is what makes it right for normals. */
    fun rotate(v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )

    /**
     * Transforms a point and keeps w, for the projection step.
     *
     * The w is the whole of perspective: dividing by it is what makes distant things smaller, and a
     * pipeline that drops it draws an orthographic picture that looks subtly like a flat sticker.
     */
    fun project(v: Vec3): FloatArray = floatArrayOf(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14],
        m[3] * v.x + m[7] * v.y + m[11] * v.z + m[15],
    )

    companion object {
        const val SIZE = 16

        val IDENTITY = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        fun translation(t: Vec3) = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                t.x, t.y, t.z, 1f,
            ),
        )

        fun scale(s: Vec3) = Mat4(
            floatArrayOf(
                s.x, 0f, 0f, 0f,
                0f, s.y, 0f, 0f,
                0f, 0f, s.z, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        fun rotationX(degrees: Float): Mat4 {
            val r = degrees * DEG_TO_RAD
            val c = cos(r)
            val s = sin(r)
            return Mat4(
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, c, s, 0f,
                    0f, -s, c, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        fun rotationY(degrees: Float): Mat4 {
            val r = degrees * DEG_TO_RAD
            val c = cos(r)
            val s = sin(r)
            return Mat4(
                floatArrayOf(
                    c, 0f, -s, 0f,
                    0f, 1f, 0f, 0f,
                    s, 0f, c, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        fun rotationZ(degrees: Float): Mat4 {
            val r = degrees * DEG_TO_RAD
            val c = cos(r)
            val s = sin(r)
            return Mat4(
                floatArrayOf(
                    c, s, 0f, 0f,
                    -s, c, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /**
         * Y then X then Z, which is the order every 3D panel's three sliders imply.
         *
         * The order matters and is not a detail: rotations do not commute, so applying the same
         * three numbers in a different order gives a visibly different result, and a user who set
         * them by eye in one tool would find them wrong in another.
         */
        fun rotation(degrees: Vec3): Mat4 =
            rotationZ(degrees.z) * rotationX(degrees.x) * rotationY(degrees.y)

        /**
         * A camera looking at a point.
         *
         * Right-handed with the camera facing down −Z, the convention OpenGL uses, so the projection
         * matrix below can be the textbook one rather than a variant with hidden sign flips.
         */
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3(0f, 1f, 0f)): Mat4 {
            val forward = (target - eye).normalised()
            val right = (forward cross up).normalised()
            // Recomputed rather than taken from the argument: the supplied up is only a hint, and
            // using it directly would shear the view whenever it was not already perpendicular.
            val trueUp = right cross forward
            return Mat4(
                floatArrayOf(
                    right.x, trueUp.x, -forward.x, 0f,
                    right.y, trueUp.y, -forward.y, 0f,
                    right.z, trueUp.z, -forward.z, 0f,
                    -(right dot eye), -(trueUp dot eye), forward dot eye, 1f,
                ),
            )
        }

        /** @param fieldOfView vertical, in degrees. Lower values flatten the perspective. */
        fun perspective(fieldOfView: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / tan(fieldOfView * DEG_TO_RAD / 2f)
            return Mat4(
                floatArrayOf(
                    f / aspect, 0f, 0f, 0f,
                    0f, f, 0f, 0f,
                    0f, 0f, (far + near) / (near - far), -1f,
                    0f, 0f, 2f * far * near / (near - far), 0f,
                ),
            )
        }

        /**
         * The inverse-transpose of the upper 3×3, for transforming normals.
         *
         * Needed only when the model is scaled non-uniformly, and then it is needed badly: a normal
         * carried through the ordinary matrix stops being perpendicular to its surface, and the
         * lighting slides off the geometry in a way that reads as the shading being broken.
         */
        fun normalMatrix(model: Mat4): Mat4 {
            val m = model.m
            val a = m[0]
            val b = m[4]
            val c = m[8]
            val d = m[1]
            val e = m[5]
            val f = m[9]
            val g = m[2]
            val h = m[6]
            val i = m[10]

            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            if (abs(det) < EPSILON) return IDENTITY
            val inv = 1f / det

            // Inverse of the 3x3, then transposed — written out because the transpose is folded in
            // rather than applied afterwards, and doing both separately is where sign errors live.
            return Mat4(
                floatArrayOf(
                    (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv, 0f,
                    (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv, 0f,
                    (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }
    }
}

internal const val EPSILON = 1e-6f
internal const val DEG_TO_RAD = 0.017453292f
