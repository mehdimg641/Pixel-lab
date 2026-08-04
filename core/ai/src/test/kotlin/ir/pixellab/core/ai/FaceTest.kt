package ir.pixellab.core.ai

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The geometry half of face retouching, driven by hand-built landmarks.
 *
 * The whole reason [FaceLandmarks] is a plain data class and [FaceModel] is a one-method interface:
 * everything that decides what a lip *is*, or what "slim the face" *means*, runs here in an ordinary
 * unit test with no model file, no phone and no network. Only the thirty lines that turn pixels into
 * points need any of that.
 */
class FaceTest {

    /**
     * A synthetic face: an ellipse of mesh points with the named regions placed where they belong.
     *
     * Not a real detection, and it does not need to be. Every assertion below is about a *relation* —
     * this region is inside that one, this point moved towards the axis, this mask is soft at its
     * edge — and a relation that holds for a synthetic face holds for a real one.
     */
    private fun syntheticFace(cx: Float = 200f, cy: Float = 200f, halfWidth: Float = 100f): FaceLandmarks {
        val points = ArrayList<Vec2>(478)
        // Fill every index with a point on an ellipse, so no index is ever out of range…
        for (i in 0 until 478) {
            val angle = i * 2.0 * Math.PI / 478.0
            points += Vec2(
                cx + (halfWidth * 0.9f * cos(angle)).toFloat(),
                cy + (halfWidth * 1.2f * sin(angle)).toFloat(),
            )
        }
        // …and then place the ones the tests actually name.
        fun put(index: Int, x: Float, y: Float) { points[index] = Vec2(x, y) }

        put(10, cx, cy - halfWidth * 1.3f)        // forehead
        put(152, cx, cy + halfWidth * 1.3f)       // chin tip
        put(234, cx - halfWidth, cy)              // right cheek edge
        put(454, cx + halfWidth, cy)              // left cheek edge
        put(168, cx, cy - halfWidth * 0.3f)       // nose bridge
        put(4, cx, cy + halfWidth * 0.1f)         // nose tip
        put(468, cx - halfWidth * 0.4f, cy - halfWidth * 0.25f)  // right iris
        put(473, cx + halfWidth * 0.4f, cy - halfWidth * 0.25f)  // left iris

        // The lip ring, as a small ellipse below the nose.
        val lipCx = cx
        val lipCy = cy + halfWidth * 0.55f
        for ((n, index) in FaceRegion.LIPS.indices.withIndex()) {
            val angle = n * 2.0 * Math.PI / FaceRegion.LIPS.indices.size
            put(
                index,
                lipCx + (halfWidth * 0.3f * cos(angle)).toFloat(),
                lipCy + (halfWidth * 0.12f * sin(angle)).toFloat(),
            )
        }
        // The mouth opening, inside it.
        for ((n, index) in FaceRegion.MOUTH_INNER.indices.withIndex()) {
            val angle = n * 2.0 * Math.PI / FaceRegion.MOUTH_INNER.indices.size
            put(
                index,
                lipCx + (halfWidth * 0.18f * cos(angle)).toFloat(),
                lipCy + (halfWidth * 0.06f * sin(angle)).toFloat(),
            )
        }
        return FaceLandmarks(
            points,
            Rect(cx - halfWidth, cy - halfWidth * 1.3f, cx + halfWidth, cy + halfWidth * 1.3f),
        )
    }

    // ---- landmarks --------------------------------------------------------------------------

    @Test
    fun `a mesh too short to be a face is refused rather than read past its end`() {
        try {
            FaceLandmarks(List(50) { Vec2() }, Rect(0f, 0f, 1f, 1f))
            error("a 50-point mesh was accepted as a face")
        } catch (expected: IllegalArgumentException) {
            (expected.message?.contains("468") == true) shouldBe true
        }
    }

    @Test
    fun `face width is measured across the cheeks, in pixels`() {
        val face = syntheticFace(halfWidth = 100f)
        abs(face.faceWidth - 200f) shouldBeLessThan 0.01f
    }

    @Test
    fun `roll is zero for a level face and follows a tilt`() {
        val level = syntheticFace()
        abs(level.roll) shouldBeLessThan 0.01f

        // Tilt the eye line and nothing else.
        val tilted = level.points.toMutableList()
        tilted[FaceLandmarks.LEFT_IRIS] = Vec2(240f, 160f)
        tilted[FaceLandmarks.RIGHT_IRIS] = Vec2(160f, 200f)
        abs(FaceLandmarks(tilted, level.bounds).roll) shouldBeGreaterThan 0.3f
    }

    // ---- masks ------------------------------------------------------------------------------

    @Test
    fun `a region mask covers its own region and nothing else`() {
        val face = syntheticFace()
        val mask = FaceMask.of(face, FaceRegion.LIPS, 400, 400)
        fun at(x: Int, y: Int) = mask[y * 400 + x]

        // The centre of the mouth is inside the lip ring.
        at(200, 255) shouldBeGreaterThan 0.9f
        // The forehead is not.
        at(200, 90) shouldBeLessThan 0.01f
    }

    @Test
    fun `the mask edge is a ramp, not a step`() {
        // A hard polygon edge on a lip is visible at any zoom, because the real edge of a lip is a
        // gradient a few pixels wide and the mesh approximates the middle of it.
        val face = syntheticFace()
        val mask = FaceMask.of(face, FaceRegion.LIPS, 400, 400, feather = 0.08f)
        val row = 255
        var partial = 0
        for (x in 0 until 400) {
            val v = mask[row * 400 + x]
            if (v > 0.05f && v < 0.95f) partial++
        }
        (partial >= 4) shouldBe true
    }

    @Test
    fun `feather is measured in face widths, so one setting works at any size`() {
        // The property that lets a single slider work on a thumbnail and a forty-megapixel portrait.
        fun softPixels(halfWidth: Float, size: Int): Int {
            val face = syntheticFace(cx = size / 2f, cy = size / 2f, halfWidth = halfWidth)
            val mask = FaceMask.of(face, FaceRegion.LIPS, size, size, feather = 0.06f)
            val row = (size / 2f + halfWidth * 0.55f).toInt()
            return (0 until size).count { x ->
                val v = mask[row * size + x]
                v > 0.05f && v < 0.95f
            }
        }
        val small = softPixels(50f, 400)
        val large = softPixels(200f, 1600)
        // Four times the face, roughly four times the soft band — not a constant pixel count.
        (large > small * 2) shouldBe true
    }

    @Test
    fun `grow pushes the boundary outwards`() {
        // How a blusher covers a cheek rather than tracing its outline.
        val face = syntheticFace()
        fun covered(grow: Float): Int {
            val mask = FaceMask.of(face, FaceRegion.LIPS, 400, 400, grow = grow)
            return mask.count { it > 0.5f }
        }
        (covered(0.05f) > covered(0f)) shouldBe true
    }

    @Test
    fun `a degenerate polygon produces an empty mask rather than throwing`() {
        val mask = FaceMask.polygonMask(listOf(Vec2(1f, 1f), Vec2(2f, 2f)), 16, 16, feather = 2f)
        mask.all { it == 0f } shouldBe true
    }

    // ---- reshaping --------------------------------------------------------------------------

    private val frame = Rect(0f, 0f, 400f, 400f)

    private fun displacement(points: List<ir.pixellab.core.imaging.MovingLeastSquares.ControlPoint>) =
        points.filter { abs(it.fromX - it.toX) > 0.01f || abs(it.fromY - it.toY) > 0.01f }

    @Test
    fun `slimming pulls the jaw towards the face's own axis`() {
        val face = syntheticFace()
        val points = FaceReshape.controlPoints(
            face,
            mapOf(FaceReshape.Adjustment.SLIM_FACE to 1f),
            frame,
        )
        val movedPoints = displacement(points)
        movedPoints.isNotEmpty() shouldBe true
        // Every moved point ends nearer the vertical centre line than it began.
        for (p in movedPoints) {
            abs(p.toX - 200f) shouldBeLessThan abs(p.fromX - 200f) + 0.01f
        }
    }

    @Test
    fun `the eye line is pinned, so a jaw adjustment does not move the eyes`() {
        // The anchoring problem, which is the single most common failure of a naive face warp:
        // without pinned points the deformation has no reason to stop and quietly narrows the
        // forehead too.
        val face = syntheticFace()
        val points = FaceReshape.controlPoints(
            face,
            mapOf(FaceReshape.Adjustment.JAW to 1f),
            frame,
        )
        val irisLeft = face.leftIris
        val pinned = points.any {
            abs(it.fromX - irisLeft.x) < 60f && abs(it.fromX - it.toX) < 0.01f
        }
        pinned shouldBe true
    }

    @Test
    fun `the frame corners are pinned, so the background does not slide with the face`() {
        val face = syntheticFace()
        val points = FaceReshape.controlPoints(
            face,
            mapOf(FaceReshape.Adjustment.SLIM_FACE to 1f),
            frame,
        )
        for (corner in listOf(0f to 0f, 400f to 0f, 0f to 400f, 400f to 400f)) {
            val found = points.any {
                abs(it.fromX - corner.first) < 0.01f && abs(it.fromY - corner.second) < 0.01f &&
                    abs(it.fromX - it.toX) < 0.01f && abs(it.fromY - it.toY) < 0.01f
            }
            found shouldBe true
        }
    }

    @Test
    fun `enlarging the eyes scales about each iris, not about the face`() {
        val face = syntheticFace()
        val points = FaceReshape.controlPoints(
            face,
            mapOf(FaceReshape.Adjustment.EYES to 1f),
            frame,
        )
        val moved = displacement(points)
        moved.isNotEmpty() shouldBe true
        // Each moved point ends further from its own iris than it began — and the two irises are on
        // opposite sides, so a single face-centred scale could not satisfy both.
        for (p in moved) {
            val iris = if (p.fromX > 200f) face.leftIris else face.rightIris
            val before = kotlin.math.hypot(p.fromX - iris.x, p.fromY - iris.y)
            val after = kotlin.math.hypot(p.toX - iris.x, p.toY - iris.y)
            after shouldBeGreaterThan before - 0.01f
        }
    }

    @Test
    fun `zero on every slider produces no control points at all`() {
        // Not "produces an identity warp" — no points, so the caller can skip the resample entirely.
        // A face warped by nothing is still a face that has been resampled once, and that is visible.
        val face = syntheticFace()
        FaceReshape.controlPoints(
            face,
            mapOf(FaceReshape.Adjustment.SLIM_FACE to 0f, FaceReshape.Adjustment.EYES to 0f),
            frame,
        ).isEmpty() shouldBe true
    }

    @Test
    fun `two adjustments at once produce one point set, not two warps`() {
        // Running two warps in sequence resamples the picture twice, and two resamples of a face is
        // visible softening. Solving them together is the whole reason this takes a map.
        val face = syntheticFace()
        val both = FaceReshape.controlPoints(
            face,
            mapOf(FaceReshape.Adjustment.SLIM_FACE to 1f, FaceReshape.Adjustment.EYES to 1f),
            frame,
        )
        val slimOnly = FaceReshape.controlPoints(
            face,
            mapOf(FaceReshape.Adjustment.SLIM_FACE to 1f),
            frame,
        )
        (displacement(both).size > displacement(slimOnly).size) shouldBe true
    }

    @Test
    fun `the sliders are symmetric, so every one of them undoes itself`() {
        val face = syntheticFace()
        for (adjustment in FaceReshape.Adjustment.entries) {
            val positive = displacement(FaceReshape.controlPoints(face, mapOf(adjustment to 1f), frame))
            val negative = displacement(FaceReshape.controlPoints(face, mapOf(adjustment to -1f), frame))
            positive.size shouldBe negative.size
            positive.isNotEmpty() shouldBe true
        }
    }
}
