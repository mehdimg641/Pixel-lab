package ir.pixellab.core.mesh

import ir.pixellab.core.model.InnerShadow
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The shadow a letter's own edge throws across its face.
 *
 * The reference covers this project is measured against all have one, and nothing here produced it.
 * The face is the front-most surface of the mesh and nothing on the letter stands above it, so no
 * amount of correct lighting will ever put a shadow there — it is a piece of design, and it has to
 * be expressed as one. That is exactly what Photoshop's Inner Shadow is, and this computes it the
 * same way for the same reason: the face is flat, so its occluder is its own outline.
 *
 * ```
 * shadow = blur( outline shifted along the light ) ∩ face
 * ```
 *
 * ### Why a screen-space pass and not more geometry
 *
 * The alternative is to model it — inset the face, sink it below the rim, and let the existing
 * shading find the shadow. That is more faithful and it is the wrong trade here twice over. It
 * changes the silhouette, so a letter would gain a visible step it did not have before; and it makes
 * the effect's strength a function of the bevel's size, which is a separate control the user has
 * already set for another reason. Held apart, the two can be dialled independently, which is what
 * anybody working on a cover actually wants.
 *
 * ### The unit that matters
 *
 * Distance and size are fractions of the **letters' own height on screen**, measured from the face
 * mask itself. Not pixels, which stop meaning anything at export size, and not a fraction of the
 * frame, which changes the instant the type is reframed — and it *is* reframed, because the cast
 * shadow now pulls the camera back to hold itself.
 */
internal object InnerShadowPass {

    /**
     * @param face true wherever a face fragment won the depth test, at supersampled resolution.
     */
    fun draw(
        colour: IntArray,
        face: BooleanArray,
        spec: InnerShadow,
        width: Int,
        height: Int,
    ) {
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (i in face.indices) {
            if (!face[i]) continue
            val y = i / width
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        // No face on screen: the letter is off frame, or every fragment lost to the walls.
        if (minY > maxY) return
        val letterHeight = (maxY - minY + 1).toFloat()

        // Anticlockwise from the right, as the panel asks for it, and negated in y because the
        // buffer counts downwards. The shadow falls *away* from the light, so the outline is shifted
        // along the light's own direction to find what it covers.
        val radians = Math.toRadians(spec.angle.toDouble())
        val reach = spec.distance * letterHeight
        val dx = (-cos(radians) * reach).roundToInt()
        val dy = (sin(radians) * reach).roundToInt()

        // Everything the face is not, shifted. Sampling *from* the opposite offset is the shift:
        // reading `outside(p − d)` puts the outside at `p`, which is where the shadow belongs.
        val occluder = FloatArray(face.size)
        for (y in 0 until height) {
            val sy = (y - dy).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val sx = (x - dx).coerceIn(0, width - 1)
                occluder[y * width + x] = if (face[sy * width + sx]) 0f else 1f
            }
        }

        Blur.box(occluder, width, height, (spec.size * letterHeight).roundToInt())

        val r = spec.color.r
        val g = spec.color.g
        val b = spec.color.b
        for (i in face.indices) {
            // Kept inside the face. A letter's walls and bevel have their own shading and are not
            // the surface this shadow lands on; bleeding onto them is the mistake that makes an
            // inner shadow read as grime rather than as depth.
            if (!face[i]) continue
            val a = occluder[i] * spec.opacity
            if (a <= 0f) continue
            val pixel = colour[i]
            // Over the shaded colour, in the space it is already in. The face has been tone-mapped
            // and encoded by this point, so decoding it back to linear to darken it and encoding it
            // again would cost two pows per pixel to arrive at a difference nobody can see under a
            // soft black shadow.
            colour[i] = (pixel and ALPHA) or
                (mix((pixel shr 16) and 0xFF, r, a) shl 16) or
                (mix((pixel shr 8) and 0xFF, g, a) shl 8) or
                mix(pixel and 0xFF, b, a)
        }
    }

    private fun mix(channel: Int, towards: Float, amount: Float): Int {
        val target = towards * 255f
        return (channel + (target - channel) * amount).roundToInt().coerceIn(0, 255)
    }

    private const val ALPHA = 0xFF shl 24
}
