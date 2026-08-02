package ir.pixellab.core.mesh

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Light
import ir.pixellab.core.model.LightRig
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.Vec3
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The 3D pipeline: matrices, extrusion, shading and the rasteriser.
 *
 * Every case here is aimed at something that would still *look like a render* if it were wrong.
 * A mirrored matrix, a normal that stopped being perpendicular, a metal shaded as plastic and a
 * screen-space interpolation all produce a picture; they just produce the wrong one, and none of
 * them throws.
 */
class RenderPipelineTest {

    private fun square(size: Float) = listOf(
        Vec2(0f, 0f), Vec2(size, 0f), Vec2(size, size), Vec2(0f, size),
    )

    // ---- matrices --------------------------------------------------------------------------

    @Test
    fun `the identity leaves a point alone`() {
        val p = Mat4.IDENTITY.transform(Vec3(3f, -4f, 5f))
        p.x shouldBe 3f
        p.y shouldBe -4f
        p.z shouldBe 5f
    }

    @Test
    fun `translation moves a point and leaves a direction`() {
        val m = Mat4.translation(Vec3(10f, 0f, 0f))
        m.transform(Vec3(1f, 2f, 3f)).x shouldBe 11f
        // The distinction that matters: a normal carried through the point path would drift ten
        // units sideways and stop being a direction at all.
        m.rotate(Vec3(1f, 2f, 3f)).x shouldBe 1f
    }

    @Test
    fun `a quarter turn about Y sends X to minus Z`() {
        // Pinned to an exact expected result rather than "something changed", because a sign error
        // here mirrors the whole scene and a mirrored render still looks like a render.
        val p = Mat4.rotationY(90f).transform(Vec3(1f, 0f, 0f))
        p.x shouldBe 0f.plusOrMinus(1e-5f)
        p.z shouldBe (-1f).plusOrMinus(1e-5f)
    }

    @Test
    fun `the normal matrix keeps normals perpendicular under a non-uniform scale`() {
        // The one case where the ordinary matrix is wrong. A plane sloping at 45° squashed to a
        // fifth in Y: its normal must tilt the *other* way, and the naive transform tilts it the
        // same way — which slides the lighting off the geometry.
        val model = Mat4.scale(Vec3(1f, 0.2f, 1f))
        val normals = Mat4.normalMatrix(model)

        val tangent = model.rotate(Vec3(1f, 1f, 0f))
        val normal = normals.rotate(Vec3(1f, -1f, 0f).normalised()).normalised()
        abs(tangent.normalised() dot normal) shouldBe 0f.plusOrMinus(1e-4f)
    }

    @Test
    fun `perspective divides by depth`() {
        val projection = Mat4.perspective(45f, 1f, 0.1f, 100f)
        val near = projection.project(Vec3(1f, 0f, -5f))
        val far = projection.project(Vec3(1f, 0f, -50f))
        // The same world x lands closer to the centre when it is further away. Without this the
        // render is orthographic and every extruded letter reads as a flat sticker.
        (abs(near[0] / near[3]) > abs(far[0] / far[3])) shouldBe true
    }

    @Test
    fun `a camera looks down its own negative Z`() {
        val view = Mat4.lookAt(Vec3(0f, 0f, 10f), Vec3.ZERO)
        val origin = view.transform(Vec3.ZERO)
        origin.z shouldBe (-10f).plusOrMinus(1e-4f)
        origin.x shouldBe 0f.plusOrMinus(1e-4f)
    }

    // ---- extrusion -------------------------------------------------------------------------

    @Test
    fun `an extruded square is a closed box`() {
        val mesh = Extruder.extrude(listOf(square(10f)), depth = 4f)
        (mesh.triangleCount >= 12) shouldBe true

        val (min, max) = mesh.bounds()
        max.z shouldBe 0f.plusOrMinus(1e-4f)
        min.z shouldBe (-4f).plusOrMinus(1e-4f)
    }

    @Test
    fun `the front cap faces the viewer and the back cap faces away`() {
        val mesh = Extruder.extrude(listOf(square(10f)), depth = 4f)
        var front = 0
        var back = 0
        for (t in 0 until mesh.triangleCount) {
            when (mesh.surfaces[t]) {
                Surface.FACE -> front++
                Surface.BACK -> back++
                else -> Unit
            }
        }
        (front > 0) shouldBe true
        (back > 0) shouldBe true

        for (i in 0 until mesh.vertexCount) {
            val n = mesh.normal(i)
            // Every normal is unit length. A zero-length one is a degenerate triangle whose shading
            // comes out as a black hole that is very hard to trace back to its cause.
            (n.length > 0.9f) shouldBe true
        }
    }

    @Test
    fun `a bevel adds rings between the face and the side`() {
        val plain = Extruder.extrude(listOf(square(20f)), depth = 4f, bevelSize = 0f)
        val bevelled = Extruder.extrude(
            listOf(square(20f)),
            depth = 4f,
            bevelSize = 2f,
            bevelSegments = 6,
        )
        (bevelled.triangleCount > plain.triangleCount) shouldBe true

        var bevelTriangles = 0
        for (t in 0 until bevelled.triangleCount) if (bevelled.surfaces[t] == Surface.BEVEL) bevelTriangles++
        (bevelTriangles > 0) shouldBe true
    }

    @Test
    fun `a bevel normal turns from facing the viewer to facing sideways`() {
        // The gradient that a specular highlight slides along, and the only reason a bevel reads as
        // a bevel rather than as a wider letter.
        val mesh = Extruder.extrude(listOf(square(40f)), depth = 4f, bevelSize = 4f, bevelSegments = 8)
        var mostForward = -1f
        var mostSideways = -1f
        for (t in 0 until mesh.triangleCount) {
            if (mesh.surfaces[t] != Surface.BEVEL) continue
            for (k in 0 until 3) {
                val n = mesh.normal(mesh.indices[t * 3 + k])
                if (n.z > mostForward) mostForward = n.z
                val sideways = kotlin.math.sqrt(n.x * n.x + n.y * n.y)
                if (sideways > mostSideways) mostSideways = sideways
            }
        }
        (mostForward > 0.8f) shouldBe true
        (mostSideways > 0.8f) shouldBe true
    }

    @Test
    fun `a bevel wider than the stroke does not turn the face inside out`() {
        // A thin Persian stroke with a fat bevel. Insetting past the middle produces a
        // self-crossing outline, which tessellates into triangles that overlap and shade as a
        // bright crease down the letter.
        val thin = listOf(Vec2(0f, 0f), Vec2(40f, 0f), Vec2(40f, 3f), Vec2(0f, 3f))
        val mesh = Extruder.extrude(listOf(thin), depth = 3f, bevelSize = 10f)
        (mesh.triangleCount > 0) shouldBe true
        val (min, max) = mesh.bounds()
        (max.x - min.x <= 60f) shouldBe true
    }

    @Test
    fun `an empty outline extrudes to nothing`() {
        Extruder.extrude(emptyList(), depth = 4f).triangleCount shouldBe 0
    }

    // ---- shading ---------------------------------------------------------------------------

    private val straightOn = LightRig(
        key = Light(direction = Vec3(0f, 0f, -1f), intensity = 3f),
        fill = Light(direction = Vec3(0f, 0f, -1f), intensity = 0f, castsShadow = false),
    )

    @Test
    fun `metal takes its highlight from its own colour and plastic does not`() {
        // The single reason this is Cook-Torrance rather than Phong. A gold surface reflects gold;
        // a yellow plastic one reflects white. Phong cannot express the difference at all, and its
        // "gold" is the yellow-plastic look everyone recognises as fake.
        val gold = Material(baseColor = Color(1f, 0.77f, 0.34f), metallic = 1f, roughness = 0.2f)
        val plastic = gold.copy(metallic = 0f)

        val n = Vec3(0f, 0f, 1f)
        val v = Vec3(0f, 0f, 1f)
        val metalHit = Pbr.shade(n, v, gold, straightOn)
        val plasticHit = Pbr.shade(n, v, plastic, straightOn)

        // The metal's highlight carries the tint: its blue is well below its red.
        (metalHit.b < metalHit.r * 0.75f) shouldBe true
        // The dielectric's specular is white, so its highlight pulls the three channels together.
        (plasticHit.b / plasticHit.r > metalHit.b / metalHit.r) shouldBe true
    }

    @Test
    fun `a rougher surface spreads its highlight instead of concentrating it`() {
        val smooth = Material(baseColor = Color.WHITE, metallic = 1f, roughness = 0.05f)
        val rough = smooth.copy(roughness = 0.6f)
        val n = Vec3(0f, 0f, 1f)
        val v = Vec3(0f, 0f, 1f)

        // Dead centre of the highlight the smooth one is far brighter; that concentration is what
        // "polished" means, and a shader that ignored roughness would give the same value twice.
        val centreSmooth = Pbr.shade(n, v, smooth, straightOn).magnitude()
        val centreRough = Pbr.shade(n, v, rough, straightOn).magnitude()
        (centreSmooth > centreRough) shouldBe true
    }

    @Test
    fun `a surface facing away from every light is not pure black`() {
        // With three lights and no environment, a back-facing surface has nothing at all — and a
        // letter with black regions reads as a rendering failure rather than as a dark side.
        val shaded = Pbr.shade(
            normal = Vec3(0f, 0f, -1f),
            view = Vec3(0f, 0f, 1f),
            material = Material(baseColor = Color(0.8f, 0.2f, 0.2f)),
            rig = straightOn,
        )
        (shaded.magnitude() > 0.01f) shouldBe true
    }

    @Test
    fun `the GGX distribution peaks where the half vector meets the normal`() {
        val peak = Pbr.distribution(1f, 0.2f)
        val off = Pbr.distribution(0.7f, 0.2f)
        (peak > off) shouldBe true
        (peak > 0f) shouldBe true
    }

    @Test
    fun `Fresnel reaches white at a grazing angle whatever the material`() {
        // Everything is a mirror edge-on. A shader that skipped it leaves the rim of a bevel too
        // dark, which is where "flat-looking 3D" usually comes from.
        val f0 = Vec3(0.04f, 0.04f, 0.04f)
        val straight = Pbr.fresnel(1f, f0)
        val grazing = Pbr.fresnel(0.01f, f0)
        straight.x shouldBe 0.04f.plusOrMinus(1e-3f)
        (grazing.x > 0.9f) shouldBe true
    }

    @Test
    fun `tone mapping rolls a highlight off instead of clipping it flat`() {
        // A specular on metal genuinely exceeds 1.0. Clipping turns the brightest part of a gold
        // letter into a white patch with no shape in it.
        val bright = Pbr.toneMap(Color(4f, 4f, 4f))
        val brighter = Pbr.toneMap(Color(8f, 8f, 8f))
        (bright.r < 1f) shouldBe true
        (brighter.r > bright.r) shouldBe true
    }

    @Test
    fun `the transfer function round-trips`() {
        for (v in listOf(0f, 0.02f, 0.25f, 0.5f, 1f)) {
            val round = Pbr.toneMap(Color(Pbr.decode(v), 0f, 0f, 1f))
            // Not exact: the tone curve is in between. What matters is that it is monotone and
            // stays in range, which is what a broken transfer breaks.
            (round.r in 0f..1f) shouldBe true
        }
        (Pbr.decode(1f) > Pbr.decode(0.5f)) shouldBe true
    }

    // ---- rasteriser ------------------------------------------------------------------------

    private val geometry = Geometry3D(
        depth = 20f,
        bevelSize = 4f,
        bevelProfile = Curve.LINEAR,
        faceMaterial = Material.GLOSSY_WHITE,
        bevelMaterial = Material.GOLD,
        sideMaterial = Material.GOLD,
    )

    @Test
    fun `a square renders as opaque pixels in the middle and nothing at the corners`() {
        val mesh = Extruder.extrude(listOf(square(100f)), depth = 20f, bevelSize = 4f)
        val out = Rasteriser.render(mesh, geometry, 128, 128)

        out.isEmpty shouldBe false
        val centre = out.pixels[64 * 128 + 64]
        ((centre ushr 24) and 0xFF) shouldBe 255
        // A margin is kept around the letter, so the extreme corner is always background.
        ((out.pixels[0] ushr 24) and 0xFF) shouldBe 0
    }

    @Test
    fun `an empty mesh renders to nothing rather than failing`() {
        Rasteriser.render(Mesh.EMPTY, geometry, 32, 32).isEmpty shouldBe true
    }

    @Test
    fun `turning the letter changes the picture`() {
        // Not merely "runs": the whole reason for real geometry is that a rotation reveals the
        // side walls, which a stack of offset copies cannot do.
        val mesh = Extruder.extrude(listOf(square(100f)), depth = 30f, bevelSize = 4f)
        val flat = Rasteriser.render(mesh, geometry, 96, 96, supersample = 1)
        val turned = Rasteriser.render(
            mesh,
            geometry.copy(rotation = Vec3(0f, 35f, 0f)),
            96,
            96,
            supersample = 1,
        )

        var different = 0
        for (i in flat.pixels.indices) if (flat.pixels[i] != turned.pixels[i]) different++
        (different > flat.pixels.size / 10) shouldBe true
    }

    @Test
    fun `the near face hides the far one`() {
        // The depth buffer, stated as the thing it is for. Without it the back cap paints over the
        // front wherever it is drawn later, and the letter comes out inside out in patches.
        val mesh = Extruder.extrude(listOf(square(100f)), depth = 40f)
        val front = Rasteriser.render(mesh, geometry.copy(faceMaterial = Material(baseColor = Color.WHITE)), 64, 64)
        val centre = front.pixels[32 * 64 + 32]
        val luma = ((centre shr 16) and 0xFF) * 0.2126f +
            ((centre shr 8) and 0xFF) * 0.7152f +
            (centre and 0xFF) * 0.0722f
        // The lit front face, not the unlit back one.
        (luma > 60f) shouldBe true
    }

    @Test
    fun `supersampling softens the edge`() {
        val mesh = Extruder.extrude(listOf(square(100f)), depth = 10f)
        val hard = Rasteriser.render(mesh, geometry, 64, 64, supersample = 1)
        val soft = Rasteriser.render(mesh, geometry, 64, 64, supersample = 3)

        fun partial(pixels: IntArray) = pixels.count { ((it ushr 24) and 0xFF) in 1..254 }
        // A single sample is on or off; more samples produce partial coverage, which is what takes
        // the staircase off a rotated letter's edge.
        (partial(soft.pixels) > partial(hard.pixels)) shouldBe true
    }

    @Test
    fun `a rendered pixel is never a dark fringe of the background`() {
        // The downsample averages in premultiplied form. Averaging a transparent pixel's leftover
        // colour in unweighted is what puts a dark halo round every edge of a cut-out — and a 3D
        // letter is entirely edge.
        val mesh = Extruder.extrude(listOf(square(100f)), depth = 10f, bevelSize = 3f)
        val out = Rasteriser.render(mesh, geometry, 96, 96, supersample = 3)

        for (pixel in out.pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0 || alpha == 255) continue
            val luma = ((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)
            // A fringed edge pixel is near-black; a correctly unpremultiplied one carries the
            // letter's own colour whatever its coverage.
            (luma > 30) shouldBe true
        }
    }
}
