package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.mesh.Extruder
import ir.pixellab.core.mesh.Surface
import ir.pixellab.core.mesh.Tessellator
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.vector.PathMath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * The front of a letter is solid.
 *
 * This is the test that should have existed before the 3D renderer shipped, and its absence is why
 * a real defect survived so long. Ear clipping used to abandon a polygon it could not find an ear
 * in and emit the triangles it had, which on real glyph outlines lost between a fifth and two thirds
 * of every cap — a word whose letters were hollow shells. Nothing caught it: the mesh is closed, the
 * winding is right, the triangle count is large, and every existing assertion passed.
 *
 * **The measure has to be area, not triangles.** A count says nothing, because the same cap can be
 * described by two hundred triangles or ten thousand depending only on how finely the curves were
 * flattened — and the fix for this defect changed exactly that number by a factor of forty. Area is
 * invariant to it: however the cap is cut up, the pieces must add up to the shape.
 *
 * Real words in a real font, because the failure needed a real outline to appear at all. Every
 * synthetic fixture in the geometry module's own tests is a rectangle, and a rectangle has four
 * points and no collinear runs, so it triangulates perfectly however broken the clip is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class CapCoverageTest {

    private fun vazirmatn(): FontFile {
        val file = File.createTempFile("vazirmatn", ".ttf")
        file.deleteOnExit()
        checkNotNull(javaClass.classLoader?.getResourceAsStream("vazirmatn.ttf")) {
            "the test font is missing from engine/android/src/test/resources"
        }.use { input -> file.outputStream().use { input.copyTo(it) } }
        return FontFile(
            path = file.absolutePath,
            family = "Vazirmatn",
            subfamily = "Regular",
            postScriptName = "Vazirmatn-Regular",
            fullName = "Vazirmatn Regular",
            weight = 400,
            italic = false,
            axes = emptyMap(),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    private fun contoursOf(word: String): List<List<Vec2>> {
        val spec = TextSpec(text = word, font = FontRef("Vazirmatn"), size = 240f)
        val outline = TextToShape.outlineOf(spec, vazirmatn(), TextRasterizer())
        return PathMath.flatten(outline)
            .map { contour -> contour.map { Vec2(it.x, -it.y) } }
            .filter { it.size >= 3 }
    }

    /** The filled area of the outline: outer contours less the counters inside them. */
    private fun outlineArea(contours: List<List<Vec2>>): Double =
        abs(contours.sumOf { Tessellator.signedArea(it).toDouble() })

    /** The area the front cap's triangles actually cover. */
    private fun capArea(contours: List<List<Vec2>>, bevel: Float): Double {
        val mesh = Extruder.extrude(contours = contours, depth = 10f, bevelSize = bevel)
        var area = 0.0
        for (t in 0 until mesh.triangleCount) {
            if (mesh.surfaces[t] != Surface.FACE) continue
            val a = mesh.position(mesh.indices[t * 3])
            val b = mesh.position(mesh.indices[t * 3 + 1])
            val c = mesh.position(mesh.indices[t * 3 + 2])
            area += abs(((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2.0)
        }
        return area
    }

    @Test
    fun `an unbevelled word has a completely solid front`() {
        // With no bevel the cap is the outline itself, so the answer is exactly 100% and there is
        // nothing to allow for. Both scripts, because the defect hit both and the Latin case hit
        // hardest — its straight stems are where the flattener strings out the collinear runs that
        // used to stall the clip.
        for (word in listOf("TREND", "کاربیست")) {
            val contours = contoursOf(word)
            val covered = capArea(contours, bevel = 0f) / outlineArea(contours)
            check(covered > 0.99 && covered < 1.01) {
                "«$word» has a cap covering ${"%.1f".format(covered * 100)}% of its outline"
            }
        }
    }

    @Test
    fun `a bevelled word loses only what the bevel insets`() {
        // A bevel moves the cap inward, so the cap is legitimately smaller than the outline and the
        // exact figure depends on how thick the strokes are. What is not legitimate is either end
        // of the range: far below says the clip is dropping the cap again, and *above* the outline
        // says the inset has folded over itself and is drawing triangles on top of each other —
        // which is how a hole bridged into the wrong letter showed up, as 108% coverage.
        for (word in listOf("TREND", "کاربیست")) {
            val contours = contoursOf(word)
            val covered = capArea(contours, bevel = 2.9f) / outlineArea(contours)
            check(covered > 0.6) {
                "«$word» lost its cap to the bevel: ${"%.1f".format(covered * 100)}%"
            }
            check(covered < 1.0) {
                "«$word» has an inset cap larger than its outline — the offset folded: " +
                    "${"%.1f".format(covered * 100)}%"
            }
        }
    }
}
