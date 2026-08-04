package ir.pixellab.core.ai

import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * Where a face is, in enough detail to edit it.
 *
 * This is the seam that unblocks a whole column of the feature audit. Every automatic retouch in
 * AirBrush, Hypic and PicsArt — lipstick, teeth whitening, eye brightening, face slimming, red-eye —
 * is the same two steps: *find the region*, then run a pixel operation we already have on it. We had
 * every one of those pixel operations and none of the finding, which is why the audit's answer for
 * all of them was the same sentence: "the manual tool is complete; the detection is not there."
 *
 * Split the way [SegmentationModel] is split, and for the same reason. The geometry — regions,
 * masks, measurements — is pure Kotlin here, where a test can hand it a set of points and assert on
 * the answer. The model that produces those points is an interface, implemented once against
 * MediaPipe in the Android module. So the half that decides what a lip *is* runs in every test run,
 * and the half that needs a 3.7 MB file and a phone is thirty lines.
 *
 * The point indices below are MediaPipe's canonical 478-point face mesh, which is a published
 * topology rather than an artefact of one model — the same numbering is used by every implementation
 * of it, so a different detector producing the same mesh drops straight in.
 */
data class FaceLandmarks(
    /** 478 points in image pixels. Z is relative depth, kept because eye and nose work uses it. */
    val points: List<Vec2>,
    /** The detector's own bounding box, which is not the same as the hull of the points. */
    val bounds: Rect,
) {
    init {
        require(points.size >= MIN_POINTS) {
            "a face mesh needs at least $MIN_POINTS points, got ${points.size}"
        }
    }

    operator fun get(index: Int): Vec2 = points[index]

    /** The closed outline of a named region, in image pixels. */
    fun outline(region: FaceRegion): List<Vec2> = region.indices.map { points[it] }

    /**
     * How wide the face is at the cheekbones, in pixels.
     *
     * Every retouch amount that is not a fraction has to be expressed in these — a two-pixel lip
     * feather is invisible on a portrait and destroys a thumbnail. Scaling by the face rather than
     * by the image is what makes one slider setting mean the same thing on both.
     */
    val faceWidth: Float get() = (points[LEFT_CHEEK] - points[RIGHT_CHEEK]).length

    /** Distance between the pupils — the other natural unit, and the one eye work uses. */
    val eyeDistance: Float get() = (points[LEFT_IRIS] - points[RIGHT_IRIS]).length

    val leftIris: Vec2 get() = points[LEFT_IRIS]
    val rightIris: Vec2 get() = points[RIGHT_IRIS]

    /**
     * The tilt of the face in the image plane, in radians.
     *
     * From the eye line rather than from the nose, because the eyes stay a fixed distance apart as
     * the head turns and the nose does not.
     */
    val roll: Float
        get() {
            val d = points[LEFT_IRIS] - points[RIGHT_IRIS]
            return kotlin.math.atan2(d.y, d.x)
        }

    companion object {
        /**
         * The mesh without irises. The iris points are an optional extra head on the model, and a
         * face is still fully editable without them — only the eye-colour work needs them.
         */
        const val MIN_POINTS = 468

        const val LEFT_IRIS = 473
        const val RIGHT_IRIS = 468
        const val LEFT_CHEEK = 454
        const val RIGHT_CHEEK = 234
    }
}

/**
 * A named part of a face, as a closed loop of mesh indices.
 *
 * These loops are the published contours of the canonical mesh. Writing them out is unglamorous and
 * it is the whole reason the rest of this file is short: once a region is a polygon, a lipstick is a
 * fill, a teeth whitening is a saturation change, and a face slim is a warp — all of which exist.
 */
enum class FaceRegion(val indices: IntArray, val persianLabel: String) {

    /** The jaw and forehead outline. Everything skin-coloured is inside it. */
    FACE(
        intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400,
            377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
        ),
        "کل صورت",
    ),

    /** Outer edge of both lips together — where colour goes. */
    LIPS(
        intArrayOf(
            61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291,
            375, 321, 405, 314, 17, 84, 181, 91, 146,
        ),
        "لب",
    ),

    /** The opening between the lips. What is inside it is teeth, when the mouth is open. */
    MOUTH_INNER(
        intArrayOf(
            78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
            324, 318, 402, 317, 14, 87, 178, 88, 95,
        ),
        "داخل دهان",
    ),

    LEFT_EYE(
        intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398),
        "چشم چپ",
    ),

    RIGHT_EYE(
        intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246),
        "چشم راست",
    ),

    LEFT_BROW(
        intArrayOf(276, 283, 282, 295, 285, 336, 296, 334, 293, 300),
        "ابروی چپ",
    ),

    RIGHT_BROW(
        intArrayOf(46, 53, 52, 65, 55, 107, 66, 105, 63, 70),
        "ابروی راست",
    ),

    /** The lid above the eye, where shadow is painted. */
    LEFT_LID(
        intArrayOf(263, 466, 388, 387, 386, 385, 384, 398, 362, 341, 256, 252, 253, 254, 339, 255),
        "پلک چپ",
    ),

    RIGHT_LID(
        intArrayOf(33, 246, 161, 160, 159, 158, 157, 173, 133, 112, 26, 22, 23, 24, 110, 25),
        "پلک راست",
    ),

    NOSE(
        intArrayOf(
            168, 193, 245, 128, 114, 217, 198, 209, 49, 48, 219, 166, 79, 20, 60, 99, 97,
            2, 326, 328, 290, 250, 309, 392, 439, 278, 279, 429, 420, 437, 343, 357, 465, 417,
        ),
        "بینی",
    ),

    /** The apple of each cheek, where blush goes. Left. */
    LEFT_CHEEK(
        intArrayOf(345, 352, 376, 433, 416, 434, 430, 431, 262, 428, 199, 371, 266, 425, 280, 330),
        "گونهٔ چپ",
    ),

    RIGHT_CHEEK(
        intArrayOf(116, 123, 147, 213, 192, 214, 210, 211, 32, 208, 199, 142, 36, 205, 50, 101),
        "گونهٔ راست",
    ),
    ;

    /** The bounding box of this region for a given face, in image pixels. */
    fun bounds(face: FaceLandmarks): Rect {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in indices) {
            val p = face[i]
            left = min(left, p.x)
            top = min(top, p.y)
            right = max(right, p.x)
            bottom = max(bottom, p.y)
        }
        return Rect(left, top, right, bottom)
    }
}

/**
 * Turning a region into a coverage mask.
 *
 * The masks are what every face tool actually consumes, and two properties decide whether the result
 * looks retouched or looks like a sticker:
 *
 * - **The edge is feathered, always.** A hard polygon edge on a lip is visible at any zoom, because
 *   the real edge of a lip is a gradient a few pixels wide and the mesh is an approximation of the
 *   middle of it.
 * - **The feather is measured in face widths, not pixels.** The same setting has to work on a
 *   thumbnail and on a forty-megapixel portrait.
 */
object FaceMask {

    /**
     * Coverage in 0..1 for one region, at image resolution.
     *
     * @param feather as a fraction of the face's width. The default is the width of the soft edge of
     *   a real lip line, measured off portraits rather than chosen.
     * @param grow pushes the boundary outwards by this fraction of the face width before feathering,
     *   which is how a blusher covers the cheek rather than tracing its outline.
     */
    fun of(
        face: FaceLandmarks,
        region: FaceRegion,
        width: Int,
        height: Int,
        feather: Float = DEFAULT_FEATHER,
        grow: Float = 0f,
    ): FloatArray {
        val polygon = face.outline(region)
        val scale = face.faceWidth
        return polygonMask(polygon, width, height, feather * scale, grow * scale)
    }

    /**
     * The signed-distance rasteriser the masks are built on.
     *
     * Distance rather than a scanline fill, because a fill gives a binary answer and the whole point
     * is the ramp at the edge. Working from the distance to the polygon means grow and feather are
     * one expression instead of a dilation pass followed by a blur pass — and it means the ramp is
     * genuinely smooth rather than a blurred staircase.
     */
    fun polygonMask(
        polygon: List<Vec2>,
        width: Int,
        height: Int,
        feather: Float,
        grow: Float = 0f,
    ): FloatArray {
        val out = FloatArray(width * height)
        if (polygon.size < 3) return out

        // Only the rows and columns the polygon can reach, plus the feather. On a 4000-pixel
        // portrait a lip covers about one per cent of the frame, and walking the whole image per
        // region would make a five-region makeup pass a hundred times slower than the work in it.
        val margin = grow + feather + 2f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in polygon) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val x0 = max(0, (minX - margin).toInt())
        val y0 = max(0, (minY - margin).toInt())
        val x1 = min(width - 1, (maxX + margin).toInt() + 1)
        val y1 = min(height - 1, (maxY + margin).toInt() + 1)
        val half = max(feather, MIN_FEATHER) * 0.5f

        for (y in y0..y1) {
            for (x in x0..x1) {
                val px = x + 0.5f
                val py = y + 0.5f
                val inside = contains(polygon, px, py)
                val distance = edgeDistance(polygon, px, py)
                // Signed: negative inside. Grow moves the zero crossing outwards.
                val signed = (if (inside) -distance else distance) - grow
                out[y * width + x] = smoothstep(half, -half, signed)
            }
        }
        return out
    }

    /** Even-odd crossing count. The face contours are simple loops, so winding rules do not differ. */
    private fun contains(polygon: List<Vec2>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun edgeDistance(polygon: List<Vec2>, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val d = segmentDistance(polygon[j], polygon[i], x, y)
            if (d < best) best = d
            j = i
        }
        return best
    }

    private fun segmentDistance(a: Vec2, b: Vec2, x: Float, y: Float): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val lengthSquared = vx * vx + vy * vy
        if (lengthSquared < 1e-6f) return kotlin.math.hypot(x - a.x, y - a.y)
        val t = (((x - a.x) * vx + (y - a.y) * vy) / lengthSquared).coerceIn(0f, 1f)
        return kotlin.math.hypot(x - (a.x + vx * t), y - (a.y + vy * t))
    }

    private fun smoothstep(edge0: Float, edge1: Float, v: Float): Float {
        if (edge1 == edge0) return if (v >= edge1) 1f else 0f
        val t = ((v - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** About the width of the soft edge of a real lip line, as a fraction of face width. */
    const val DEFAULT_FEATHER = 0.012f

    /** A mask with no feather at all would be a polygon edge, which is visible at any zoom. */
    private const val MIN_FEATHER = 1f
}

/**
 * Finding faces. The half that needs a model file and a phone.
 *
 * Deliberately tiny: everything above works on a `FaceLandmarks` however it was produced, so a test
 * builds one by hand and never touches this. The Android implementation is in `engine:android`.
 */
fun interface FaceModel {
    /** Every face in the image, largest first. Empty when there is none — never null. */
    fun detect(pixels: IntArray, width: Int, height: Int): List<FaceLandmarks>
}
