package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.mesh.Extruder
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

/**
 * The caps and the walls of an extruded letter are built on the same points.
 *
 * They were not, and the way it failed is the reason this test is worth its weight. The tessellator
 * dropped the flattener's redundant points before capping; the wall builder did not. So a cap was a
 * coarse polygon while its own wall followed every point of the original curve, the two shared no
 * vertex anywhere, and the "solid" had some twenty thousand boundary edges — a wedge of nothing
 * between every cap edge and the wall below it.
 *
 * None of that was visible. A straight extrusion shows none of those seams, because every one of
 * them is edge-on to the camera; the letters looked correct for as long as the depth ran straight
 * back. Leaning the extrusion swung the seams into view all at once and the letters came apart.
 *
 * **Counting boundary edges is the measure, and it has to weld by position first.** Caps and walls
 * are made by different code paths and each mints its own vertices, so the same corner carries two
 * indices; an index-based test reports a continuous surface as full of holes and can never
 * distinguish that from the real thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class MeshClosureTest {

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

    @Test
    fun `an extruded word is a solid, not a set of loose surfaces`() {
        for (word in listOf("TREND", "کاربیست")) {
            val contours = contoursOf(word)
            // The points the letter actually needs, which is what both caps and walls should be
            // built from. A seam runs once round each contour, so that is the scale a healthy
            // boundary count sits at — two orders of magnitude below the broken one.
            val corners = contours.sumOf { Tessellator.clean(it).size }

            for (bevel in listOf(0f, 8f)) {
                val mesh = Extruder.extrude(contours = contours, depth = 96f, bevelSize = bevel)

                val weld = HashMap<Long, Int>()
                val id = IntArray(mesh.vertexCount)
                for (v in 0 until mesh.vertexCount) {
                    val p = mesh.position(v)
                    // Quantised, because two paths arriving at the same corner by different
                    // arithmetic agree to within float noise rather than exactly.
                    val key = (Math.round(p.x * WELD).toLong() * PRIME + Math.round(p.y * WELD)) *
                        PRIME + Math.round(p.z * WELD)
                    id[v] = weld.getOrPut(key) { weld.size }
                }

                val directed = HashSet<Long>()
                fun key(a: Int, b: Int) = a.toLong() * PRIME + b.toLong()
                for (t in 0 until mesh.triangleCount) {
                    val a = id[mesh.indices[t * 3]]
                    val b = id[mesh.indices[t * 3 + 1]]
                    val c = id[mesh.indices[t * 3 + 2]]
                    directed += key(a, b)
                    directed += key(b, c)
                    directed += key(c, a)
                }
                var boundary = 0
                for (edge in directed) {
                    val from = (edge / PRIME).toInt()
                    val to = (edge % PRIME).toInt()
                    if (!directed.contains(key(to, from))) boundary++
                }

                check(boundary < corners * SEAMS) {
                    "«$word» at bevel $bevel is not closed: $boundary boundary edges over " +
                        "$corners outline points — the caps and the walls are on different points"
                }
            }
        }
    }

    private companion object {
        /** A thousandth of a glyph unit: far below anything visible, far above float noise. */
        const val WELD = 1000.0

        /**
         * How many open seams a healthy mesh may have per outline point.
         *
         * Not zero. The caps and the walls meet along a seam that is geometrically closed and, at
         * the bevel, joined by rings whose ends are welded here only when they land on the same
         * point — so a handful of loops around each contour is expected. What is not expected is a
         * count that scales with the *flattened* point count instead, which is what the defect this
         * guards produced: twenty thousand against a few hundred.
         */
        const val SEAMS = 4

        const val PRIME = 1_000_003L
    }
}
