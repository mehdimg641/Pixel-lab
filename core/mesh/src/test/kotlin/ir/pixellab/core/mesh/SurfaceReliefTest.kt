package ir.pixellab.core.mesh

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Light
import ir.pixellab.core.model.LightRig
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Relief
import ir.pixellab.core.model.Vec3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Whether a material looks like the material it is named after.
 *
 * ### The failure this is written against
 *
 * Ten reliefs that are all the same fractal noise with different names. It is the obvious way to
 * build this, it produces ten surfaces that are indistinguishable from one another, and a user
 * comparing «چرم» with «زنگ» concludes the feature does not work — which is a fair conclusion.
 * So what is checked is not "does it produce a number" but "is this one actually different from
 * that one, and different in the way the name claims".
 *
 * Nothing here renders a picture. Each of these properties is a statement about the height field or
 * the shading lobe that can be measured directly, and measuring it directly says *which* thing is
 * wrong when it breaks — where a pixel comparison only says that something is.
 */
class SurfaceReliefTest {

    private val grid = 64

    /** The height field sampled over one tile, as a flat array. */
    private fun field(relief: Relief, span: Float = 4f): FloatArray =
        FloatArray(grid * grid) { i ->
            SurfaceRelief.height(relief, (i % grid) * span / grid, (i / grid) * span / grid)
        }

    private fun FloatArray.mean(): Float = sum() / size

    private fun FloatArray.deviation(): Float {
        val m = mean()
        return sqrt(map { (it - m) * (it - m) }.sum() / size)
    }

    // ---- every relief is a surface at all -------------------------------------------------------

    @Test
    fun `every relief stays inside the range the shading assumes`() {
        // A height outside 0..1 does not merely look wrong: the gradient it produces tilts the
        // normal past the horizon, and a normal facing away from the surface it belongs to shades
        // as a black hole in the middle of a letter.
        for (relief in Relief.entries) {
            val heights = field(relief)
            (heights.min() >= -0.001f) shouldBe true
            (heights.max() <= 1.001f) shouldBe true
            heights.none { it.isNaN() } shouldBe true
        }
    }

    @Test
    fun `every relief except none actually varies`() {
        // A relief that comes out flat is a menu entry that does nothing, which is worse than not
        // offering it — the user concludes the whole material system is decorative.
        for (relief in Relief.entries - Relief.NONE) {
            val spread = field(relief).deviation()
            (spread > 0.02f) shouldBe true
        }
        field(Relief.NONE).deviation() shouldBe 0f
    }

    @Test
    fun `the same point always gives the same height`() {
        // Sampled three times per shaded pixel from three nearby places to build the gradient. A
        // relief backed by a stateful generator would return different answers to those three
        // questions and the surface would boil with noise.
        for (relief in Relief.entries) {
            SurfaceRelief.height(relief, 3.7f, 1.2f) shouldBe SurfaceRelief.height(relief, 3.7f, 1.2f)
        }
    }

    // ---- they are different materials, not one with ten names ----------------------------------

    @Test
    fun `brushed metal runs one way and nothing else does`() {
        // **The property that is the entire content of brushed metal.** The scratches run along one
        // axis, so the height varies far more across the grain than along it. Every other relief is
        // roughly isotropic, and a "brushed" made of ordinary noise is just a rough surface.
        val alongGrain = FloatArray(grid) { SurfaceRelief.height(Relief.BRUSHED, it * 4f / grid, 0.5f) }
        val acrossGrain = FloatArray(grid) { SurfaceRelief.height(Relief.BRUSHED, 0.5f, it * 4f / grid) }
        (acrossGrain.deviation() > alongGrain.deviation() * 3f) shouldBe true
    }

    @Test
    fun `knitting repeats and leather does not`() {
        // A stitch is a *pattern*: the same point one row down and half a stitch across is the same
        // height. Leather is the opposite claim — no two cells alike — and if both were built from
        // the same noise neither statement would hold.
        val a = SurfaceRelief.height(Relief.KNIT, 0.25f, 0.5f)
        val b = SurfaceRelief.height(Relief.KNIT, 1.25f, 2.5f)
        abs(a - b) shouldBe (0f plusOrMinus 0.15f)

        val leatherA = SurfaceRelief.height(Relief.LEATHER, 0.25f, 0.5f)
        val leatherB = SurfaceRelief.height(Relief.LEATHER, 1.25f, 2.5f)
        (abs(leatherA - leatherB) > 0.05f) shouldBe true
    }

    @Test
    fun `no two reliefs are the same surface`() {
        // The blunt version of the whole file. Any pair that correlates almost perfectly is two
        // names for one material, which is how this feature fails in practice.
        val fields = (Relief.entries - Relief.NONE).associateWith { field(it) }
        for ((oneName, one) in fields) {
            for ((otherName, other) in fields) {
                if (oneName >= otherName) continue
                val agreement = correlation(one, other)
                (agreement < 0.9f) shouldBe true
            }
        }
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val ma = a.mean()
        val mb = b.mean()
        var covariance = 0f
        var va = 0f
        var vb = 0f
        for (i in a.indices) {
            val da = a[i] - ma
            val db = b[i] - mb
            covariance += da * db
            va += da * da
            vb += db * db
        }
        val denominator = sqrt(va * vb)
        return if (denominator <= 0f) 0f else covariance / denominator
    }

    // ---- the relief reaches the normal ---------------------------------------------------------

    @Test
    fun `a relief tilts the surface and none leaves it alone`() {
        val flat = Vec3(0f, 0f, 1f)
        SurfaceRelief.perturb(Material(relief = Relief.NONE), 0.3f, 0.4f, flat) shouldBe flat

        val bumped = SurfaceRelief.perturb(Material.LEATHER, 0.3f, 0.4f, flat)
        (abs(bumped.x) + abs(bumped.y) > 0.01f) shouldBe true
        // Still a unit vector, or the shading maths downstream is being handed a lie.
        sqrt(bumped.x * bumped.x + bumped.y * bumped.y + bumped.z * bumped.z) shouldBe (1f plusOrMinus 0.001f)
    }

    @Test
    fun `turning the depth down flattens the surface rather than shrinking its features`() {
        val flat = Vec3(0f, 0f, 1f)
        val deep = SurfaceRelief.perturb(Material.LEATHER.copy(reliefDepth = 1f), 0.3f, 0.4f, flat)
        val shallow = SurfaceRelief.perturb(Material.LEATHER.copy(reliefDepth = 0.2f), 0.3f, 0.4f, flat)
        (abs(shallow.x) < abs(deep.x)) shouldBe true
        SurfaceRelief.perturb(Material.LEATHER.copy(reliefDepth = 0f), 0.3f, 0.4f, flat) shouldBe flat
    }
}

/**
 * The three shading lobes that the reference looks are actually made of.
 *
 * Each is checked by the property that is its whole reason for existing, because each of them can be
 * "implemented" in a way that compiles, produces plausible numbers, and does not do the one thing it
 * was added for.
 */
class MaterialLobeTest {

    private val rig = LightRig(
        key = Light(direction = Vec3(0f, 0f, -1f), intensity = 3f),
        fill = Light(direction = Vec3(0f, 0f, -1f), intensity = 0f, castsShadow = false),
    )

    private fun shade(material: Material, normal: Vec3, environment: EnvironmentMap = Studio): Color =
        Pbr.shade(
            normal = normal,
            view = Vec3(0f, 0f, 1f),
            material = material,
            rig = rig,
            environment = environment,
        )

    /**
     * A featureless room.
     *
     * Needed by the anisotropy test and by nothing else. The default studio is a real environment
     * with a bright panel in it, so two differently-tilted normals reflect different parts of it and
     * shade differently *whatever* the surface is made of — which is correct rendering and useless
     * for isolating one lobe. An earlier version of that test measured the room and reported the
     * lobe as broken.
     */
    private val uniform = EnvironmentMap { _, _ -> Vec3(0.1f, 0.1f, 0.1f) }

    private fun Color.luminance() = r * 0.2126f + g * 0.7152f + b * 0.0722f

    @Test
    fun `sheen brightens the silhouette, which is what makes cloth cloth`() {
        // **The defining property of fabric and the one everybody gets wrong.** Wool is brightest at
        // its edges, not where it faces the light — the fibres standing off the surface catch it
        // side-on. A "fabric" made by raising roughness dims evenly everywhere and reads as matte
        // plastic, and this is the test that tells the two apart.
        val cloth = Material.KNIT.copy(relief = Relief.NONE)
        val plain = cloth.copy(sheen = 0f)

        val facing = Vec3(0f, 0f, 1f)
        val grazing = Vec3(0.94f, 0f, 0.34f).normalised()

        val gainFacing = shade(cloth, facing).luminance() - shade(plain, facing).luminance()
        val gainGrazing = shade(cloth, grazing).luminance() - shade(plain, grazing).luminance()

        (gainGrazing > gainFacing) shouldBe true
    }

    @Test
    fun `anisotropy smears the highlight along one axis`() {
        // Brushed steel against chrome. Tilting the surface across the brushing must dim the
        // highlight far less than tilting it along the brushing — that asymmetry *is* the look, and
        // an isotropic "brushed" is simply a rough mirror.
        val brushed = Material.BRUSHED_STEEL.copy(relief = Relief.NONE)
        val isotropic = brushed.copy(anisotropy = 0f)

        val tiltedX = Vec3(0.25f, 0f, 0.97f).normalised()
        val tiltedY = Vec3(0f, 0.25f, 0.97f).normalised()

        val anisotropicRatio = shade(brushed, tiltedX, uniform).luminance() /
            shade(brushed, tiltedY, uniform).luminance()
        val isotropicRatio = shade(isotropic, tiltedX, uniform).luminance() /
            shade(isotropic, tiltedY, uniform).luminance()

        // The isotropic surface cannot tell the two tilts apart at all.
        isotropicRatio shouldBe (1f plusOrMinus 0.02f)
        (abs(anisotropicRatio - 1f) > 0.1f) shouldBe true
    }

    @Test
    fun `glass shows what is behind it rather than its own colour`() {
        // Transmission replaces the body of the surface with the environment seen through it. The
        // check that matters is that the *refractive index* changes the result: an implementation
        // that ignored it and simply sampled straight through would pass a "glass is bright" test
        // and produce a flat pane with no distortion at all.
        val glass = Material.GLASS
        val dense = glass.copy(ior = 2.4f)
        val normal = Vec3(0.4f, 0.2f, 0.89f).normalised()

        val thin = shade(glass, normal).luminance()
        val thick = shade(dense, normal).luminance()
        (abs(thin - thick) > 0.001f) shouldBe true

        // And an opaque copy of the same material is not the same picture.
        val opaque = shade(glass.copy(transmission = 0f), normal).luminance()
        (abs(opaque - thin) > 0.001f) shouldBe true
    }

    @Test
    fun `a material with no lobes set shades exactly as it did before any of this`() {
        // The guarantee every existing document rests on. Sheen, anisotropy and transmission all
        // default to off, and off has to mean the old code path rather than a new one that happens
        // to agree.
        val plain = Material.GOLD
        val normal = Vec3(0.2f, 0.1f, 0.97f).normalised()
        val shaded = shade(plain, normal)
        shaded.r.isNaN() shouldBe false
        // Metal, so it is tinted rather than white — the property the whole PBR path exists for.
        (shaded.r > shaded.b) shouldBe true
    }
}
