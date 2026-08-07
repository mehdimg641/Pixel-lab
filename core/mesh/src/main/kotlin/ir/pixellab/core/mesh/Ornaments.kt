package ir.pixellab.core.mesh

import ir.pixellab.core.model.Vec2
import kotlin.math.abs

/**
 * Tells a letter's dots and vowel marks apart from its body.
 *
 * Persian is a script whose meaning lives partly *off* the stroke. ب پ ت ث are one body with one,
 * two or three dots; ج چ ح خ likewise; and the vowel marks sit above and below the line entirely.
 * The specification asks for those to be controllable on their own (§۶.۹.۳): their own extrusion
 * depth, their own material, and the ability to float them clear of the body — which is a look no
 * tool offers because no tool knows which contour is a dot.
 *
 * There is no metadata to consult. A glyph outline arrives as a bag of closed contours with nothing
 * saying what any of them is, so this has to be decided from the geometry alone. Three cases:
 *
 * - A **counter** — the hole in ه, و, ق, ف, م, ط — sits *inside* another contour. It is part of the
 *   body and must never be treated as an ornament, or the hole in a ه would float away from the ه.
 * - A **body** contour is one of the large ones that are not inside anything.
 * - A **mark** is a small contour that is not inside anything.
 *
 * The size test is a heuristic and the honest thing is to say so. It is reliable for the case it
 * exists for — a dot is a fiftieth of a letter and a triple dot group is three of those — and it
 * will misfire on a glyph made entirely of small disjoint pieces, which in this script does not
 * occur. What makes it safe is the containment test in front of it: a hole can never be mistaken for
 * a dot however small it is, and that is the failure that would actually damage a letter.
 */
object Ornaments {

    /**
     * Which contours are dots or vowel marks.
     *
     * @return one flag per contour, in the caller's order, so it can be zipped with whatever the
     *   caller is holding rather than returning a re-sorted list nobody can match back.
     */
    fun classify(contours: List<List<Vec2>>): BooleanArray {
        val flags = BooleanArray(contours.size)
        if (contours.size < 2) return flags

        val areas = FloatArray(contours.size) { abs(Tessellator.signedArea(contours[it])) }
        val largest = areas.max()
        if (largest <= 0f) return flags

        for (i in contours.indices) {
            val contour = contours[i]
            if (contour.size < 3) continue

            // Inside something else: a counter, and part of the body whatever its size.
            val inside = contours.indices.any { j ->
                j != i && areas[j] > areas[i] && Tessellator.contains(contours[j], contour[0])
            }
            if (inside) continue

            flags[i] = areas[i] < largest * MARK_FRACTION
        }
        return flags
    }

    /**
     * How small a free-standing contour has to be, against the largest, to count as an ornament.
     *
     * A fifth, which is far above any real dot — the dot of a ب is nearer a fiftieth of it — and far
     * below the smallest body a cluster produces. The gap between those two is wide, so the exact
     * value hardly matters; what matters is that it exists at all, because a threshold tuned to a
     * hairline would flip between fonts.
     */
    private const val MARK_FRACTION = 0.2f
}

/**
 * How the dots and marks of a letter are treated differently from its body.
 *
 * Every value is relative to the body's own, so a style survives a change of depth: setting the
 * letter deeper takes the dots with it, and a user who floated the dots forward keeps them floated.
 * Absolute values would need re-tuning every time the depth slider moved.
 */
data class MarkStyle(
    /**
     * The dots' extrusion depth, as a multiple of the body's.
     *
     * One leaves them level with it. Below one they are shallower, which is the subtler and more
     * common treatment — a dot as deep as the stroke it belongs to reads as a separate block of
     * material rather than as part of the letter.
     */
    val depth: Float = 1f,

    /**
     * How far forward the dots sit, as a multiple of the body's depth.
     *
     * Zero is flush. Positive lifts them towards the viewer, which is the style the specification
     * describes as floating them — at a large enough value they separate from the body entirely and
     * cast their own shadow, and that is a look nothing else does.
     */
    val lift: Float = 0f,
) {
    val separated: Boolean get() = lift != 0f || depth != 1f

    companion object {
        /** Dots exactly like the body, which is what every other tool can do. */
        val FLUSH = MarkStyle()

        /** Shallower and slightly forward: the treatment that reads as one letter, well made. */
        val INSET = MarkStyle(depth = 0.6f, lift = 0.15f)

        /** Clear of the body, casting their own shadow. The style the specification calls new. */
        val FLOATING = MarkStyle(depth = 0.5f, lift = 0.9f)
    }
}
