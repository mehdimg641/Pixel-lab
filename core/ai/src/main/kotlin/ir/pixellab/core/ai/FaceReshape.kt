package ir.pixellab.core.ai

import ir.pixellab.core.imaging.MovingLeastSquares
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2

/**
 * Face reshaping, as control points.
 *
 * The nine sliders AirBrush calls Reshape and Hypic calls Face — slim the face, sharpen the jaw,
 * lift the cheekbones, narrow the nose, enlarge the eyes, plump the lips — are all the same thing:
 * **move a handful of mesh points and let the warp carry the rest.** The warp is
 * [MovingLeastSquares], which has existed since wave 5 and has been driven only by a finger.
 *
 * Two decisions run through all of it.
 *
 * **Rigid, always.** The affine mode allows shear, and a sheared cheek is the giveaway in every
 * over-retouched portrait. Rigid permits rotation and nothing else, which is the only mode that
 * leaves a face recognisable.
 *
 * **Anchors are as important as the moved points.** Every adjustment here also pins points that must
 * *not* move — the outer face outline for eye work, the eye line for jaw work. Without them the warp
 * has no reason to stop, and narrowing a chin quietly narrows the forehead too. That is the single
 * most common failure of a naive face-reshape tool and it is entirely an anchoring problem.
 */
object FaceReshape {

    /** One named adjustment, from −1 to +1. Zero contributes nothing. */
    enum class Adjustment(val persianLabel: String) {
        /** Pulls the jaw and cheeks inwards, towards the face's vertical axis. */
        SLIM_FACE("لاغری صورت"),

        /** The lower jaw only, so a face can be slimmed without the cheekbones moving. */
        JAW("خط فک"),

        /** Pushes the cheekbones outwards and up. */
        CHEEKBONES("گونه"),

        /** Narrows the nose about its own centre line. */
        NOSE("بینی"),

        /** Scales both eyes about their own centres. */
        EYES("اندازهٔ چشم"),

        /** Thickens the lips away from the mouth line. */
        LIPS("حجم لب"),

        /** Shortens or lengthens the chin. */
        CHIN("چانه"),

        /** Narrows the whole head, forehead included — the one people mean by "smaller face". */
        HEAD_WIDTH("عرض سر"),
    }

    /**
     * Control points for a set of adjustments applied together.
     *
     * Together rather than one at a time, and this matters: running two warps in sequence resamples
     * the picture twice, and two resamples of a face is visible softening. Solving all the
     * displacements into one control-point set means one resample however many sliders are moved.
     */
    fun controlPoints(
        face: FaceLandmarks,
        adjustments: Map<Adjustment, Float>,
        imageBounds: Rect,
    ): List<MovingLeastSquares.ControlPoint> {
        val moved = HashMap<Int, Vec2>()

        for ((adjustment, rawAmount) in adjustments) {
            val amount = rawAmount.coerceIn(-1f, 1f)
            if (amount == 0f) continue
            when (adjustment) {
                Adjustment.SLIM_FACE -> towardsAxis(face, moved, CHEEK_AND_JAW, amount * SLIM_MAX)
                Adjustment.JAW -> towardsAxis(face, moved, JAW_ONLY, amount * JAW_MAX)
                Adjustment.HEAD_WIDTH -> towardsAxis(face, moved, FULL_OUTLINE, amount * HEAD_MAX)
                Adjustment.CHEEKBONES -> outwardsAndUp(face, moved, CHEEKBONES, amount * CHEEK_MAX)
                Adjustment.NOSE -> towardsLine(
                    face, moved, NOSE_SIDES,
                    face[NOSE_BRIDGE], face[NOSE_TIP], amount * NOSE_MAX,
                )
                Adjustment.EYES -> {
                    scaleAbout(face, moved, LEFT_EYE_RING, face.leftIris, amount * EYE_MAX)
                    scaleAbout(face, moved, RIGHT_EYE_RING, face.rightIris, amount * EYE_MAX)
                }
                Adjustment.LIPS -> awayFromLine(
                    face, moved, LIP_RING,
                    face[MOUTH_LEFT], face[MOUTH_RIGHT], amount * LIP_MAX,
                )
                Adjustment.CHIN -> alongAxis(face, moved, CHIN_POINTS, amount * CHIN_MAX)
            }
        }

        if (moved.isEmpty()) return emptyList()

        val points = ArrayList<MovingLeastSquares.ControlPoint>(moved.size + ANCHOR_COUNT)
        for ((index, target) in moved) {
            val from = face[index]
            points += MovingLeastSquares.ControlPoint(from.x, from.y, target.x, target.y)
        }

        // The frame's own corners and edge midpoints, pinned. Without these the whole picture drifts
        // with the face — the background slides, and on a portrait against a doorframe that is
        // immediately visible.
        for (anchor in frameAnchors(imageBounds)) {
            points += MovingLeastSquares.ControlPoint(anchor.x, anchor.y, anchor.x, anchor.y)
        }
        // And the points on the face that this adjustment must not touch, pinned explicitly rather
        // than left to the falloff. The eye line holds still while the jaw moves, and vice versa.
        for (index in STABLE) {
            if (index in moved) continue
            val p = face[index]
            points += MovingLeastSquares.ControlPoint(p.x, p.y, p.x, p.y)
        }
        return points
    }

    // ---- the primitive motions ---------------------------------------------------------------

    /** Moves points towards the face's own vertical centre line — the slimming motion. */
    private fun towardsAxis(
        face: FaceLandmarks,
        moved: MutableMap<Int, Vec2>,
        indices: IntArray,
        amount: Float,
    ) {
        val top = face[FOREHEAD]
        val bottom = face[CHIN_TIP]
        for (i in indices) {
            val p = face[i]
            val onAxis = projectOnLine(p, top, bottom)
            moved[i] = p + (onAxis - p) * amount
        }
    }

    /** Moves points along the face's vertical axis — lengthening or shortening the chin. */
    private fun alongAxis(
        face: FaceLandmarks,
        moved: MutableMap<Int, Vec2>,
        indices: IntArray,
        amount: Float,
    ) {
        val axis = (face[CHIN_TIP] - face[FOREHEAD])
        val length = axis.length
        if (length < 1e-3f) return
        val unit = axis / length
        val step = length * amount
        for (i in indices) {
            val p = face[i]
            moved[i] = p + unit * step
        }
    }

    private fun outwardsAndUp(
        face: FaceLandmarks,
        moved: MutableMap<Int, Vec2>,
        indices: IntArray,
        amount: Float,
    ) {
        val top = face[FOREHEAD]
        val bottom = face[CHIN_TIP]
        val axis = bottom - top
        val length = axis.length.coerceAtLeast(1e-3f)
        val unit = axis / length
        for (i in indices) {
            val p = face[i]
            val onAxis = projectOnLine(p, top, bottom)
            val outward = p - onAxis
            // Out from the axis, and up along it. Cheekbones read as higher as well as wider, and
            // moving them only sideways gives a face that is broad rather than sculpted.
            moved[i] = p + outward * amount - unit * (length * amount * CHEEK_LIFT)
        }
    }

    private fun towardsLine(
        face: FaceLandmarks,
        moved: MutableMap<Int, Vec2>,
        indices: IntArray,
        a: Vec2,
        b: Vec2,
        amount: Float,
    ) {
        for (i in indices) {
            val p = face[i]
            val onLine = projectOnLine(p, a, b)
            moved[i] = p + (onLine - p) * amount
        }
    }

    private fun awayFromLine(
        face: FaceLandmarks,
        moved: MutableMap<Int, Vec2>,
        indices: IntArray,
        a: Vec2,
        b: Vec2,
        amount: Float,
    ) = towardsLine(face, moved, indices, a, b, -amount)

    private fun scaleAbout(
        face: FaceLandmarks,
        moved: MutableMap<Int, Vec2>,
        indices: IntArray,
        centre: Vec2,
        amount: Float,
    ) {
        for (i in indices) {
            val p = face[i]
            moved[i] = centre + (p - centre) * (1f + amount)
        }
    }

    /** The foot of the perpendicular from [p] to the infinite line through [a] and [b]. */
    private fun projectOnLine(p: Vec2, a: Vec2, b: Vec2): Vec2 {
        val d = b - a
        val lengthSquared = d.x * d.x + d.y * d.y
        if (lengthSquared < 1e-6f) return a
        val t = ((p.x - a.x) * d.x + (p.y - a.y) * d.y) / lengthSquared
        return a + d * t
    }

    private fun frameAnchors(bounds: Rect): List<Vec2> {
        val midX = (bounds.left + bounds.right) / 2f
        val midY = (bounds.top + bounds.bottom) / 2f
        return listOf(
            Vec2(bounds.left, bounds.top), Vec2(midX, bounds.top), Vec2(bounds.right, bounds.top),
            Vec2(bounds.left, midY), Vec2(bounds.right, midY),
            Vec2(bounds.left, bounds.bottom), Vec2(midX, bounds.bottom),
            Vec2(bounds.right, bounds.bottom),
        )
    }

    // ---- the point sets ----------------------------------------------------------------------
    //
    // Indices into MediaPipe's canonical mesh. Named rather than inlined because the same set is
    // read by two motions and a wrong index here is a face that deforms in the wrong place — the one
    // class of bug in this file that a test cannot describe more clearly than the name does.

    private const val FOREHEAD = 10
    private const val CHIN_TIP = 152
    private const val NOSE_BRIDGE = 168
    private const val NOSE_TIP = 4
    private const val MOUTH_LEFT = 61
    private const val MOUTH_RIGHT = 291

    /** Jaw and lower cheek — what "slim the face" moves. */
    private val CHEEK_AND_JAW = intArrayOf(
        58, 172, 136, 150, 149, 176, 148, 377, 400, 378, 379, 365, 397, 288,
        215, 138, 135, 169, 170, 140, 171, 175, 396, 369, 395, 394, 364, 367, 435,
    )

    /** The jaw line alone, from ear to ear under the mouth. */
    private val JAW_ONLY = intArrayOf(
        172, 136, 150, 149, 176, 148, 152, 377, 400, 378, 379, 365, 397,
    )

    /** The whole outline including the temples — the one that narrows the head. */
    private val FULL_OUTLINE = intArrayOf(
        127, 234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152,
        377, 400, 378, 379, 365, 397, 288, 361, 323, 454, 356,
    )

    private val CHEEKBONES = intArrayOf(116, 123, 147, 213, 205, 36, 345, 352, 376, 433, 425, 266)

    /** The wings of the nose, which is what narrowing moves. */
    private val NOSE_SIDES = intArrayOf(48, 115, 220, 45, 44, 278, 344, 440, 275, 274, 60, 290)

    private val LEFT_EYE_RING = intArrayOf(
        362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398,
    )

    private val RIGHT_EYE_RING = intArrayOf(
        33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246,
    )

    private val LIP_RING = intArrayOf(
        185, 40, 39, 37, 0, 267, 269, 270, 409, 375, 321, 405, 314, 17, 84, 181, 91, 146,
    )

    private val CHIN_POINTS = intArrayOf(152, 148, 176, 377, 400, 175, 199)

    /**
     * Points pinned on every adjustment.
     *
     * The eye line and the bridge of the nose. They are the landmarks a viewer measures a face
     * against, and letting them drift is what turns a retouch into a different person.
     */
    private val STABLE = intArrayOf(33, 133, 263, 362, 168, 6, 10)

    private const val ANCHOR_COUNT = 8

    // The maxima each slider reaches at ±1. Chosen so that full deflection is strong but still
    // photographic: past these the face stops being the same face, which is a failure not a feature.
    private const val SLIM_MAX = 0.18f
    private const val JAW_MAX = 0.22f
    private const val HEAD_MAX = 0.12f
    private const val CHEEK_MAX = 0.14f
    private const val CHEEK_LIFT = 0.25f
    private const val NOSE_MAX = 0.3f
    private const val EYE_MAX = 0.18f
    private const val LIP_MAX = 0.25f
    private const val CHIN_MAX = 0.06f
}
