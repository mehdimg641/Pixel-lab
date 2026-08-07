package ir.pixellab.core.imaging

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.ColorFamily
import ir.pixellab.core.model.ColorRange
import ir.pixellab.core.model.HdrMethod
import ir.pixellab.core.model.Vec3
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * What the adjustments actually compute, asserted on values.
 *
 * Until this existed the project had tests that the right numbers reached the right uniform slots
 * and no test at all of the arithmetic they fed. A shader that computes the wrong thing packs its
 * uniforms perfectly, so those tests would have passed on a Curves layer that inverted the picture.
 */
class AdjustTest {

    private fun at(colour: Vec3, adjustment: Adjustment) = Adjust.applyTo(colour, adjustment)

    private fun swatch(vararg colours: Vec3): Raster {
        val r = Raster(colours.size, 1, 3)
        for ((i, c) in colours.withIndex()) {
            r.data[i * 3] = c.x
            r.data[i * 3 + 1] = c.y
            r.data[i * 3 + 2] = c.z
        }
        return r
    }

    private val HUE_FAMILY_ORDER = listOf(
        ColorFamily.REDS, ColorFamily.YELLOWS, ColorFamily.GREENS,
        ColorFamily.CYANS, ColorFamily.BLUES, ColorFamily.MAGENTAS,
    )

    private val red = Vec3(1f, 0f, 0f)
    private val grey = Vec3(0.5f, 0.5f, 0.5f)

    // ---- the identities: every adjustment at rest has to leave the picture alone -------------

    @Test
    fun `each adjustment at its defaults is the identity`() {
        // The one property that catches the largest class of mistake. A sign error, a swapped
        // parameter or an off-by-one in a table all show up here, because at rest every one of
        // these has to be a no-op — a user who adds a layer and touches nothing must see no change.
        val resting = listOf<Adjustment>(
            Adjustment.BrightnessContrast(),
            Adjustment.Levels(),
            Adjustment.Curves(),
            Adjustment.HueSaturation(),
            Adjustment.Exposure(),
            Adjustment.Vibrance(),
            Adjustment.ColorBalance(),
            Adjustment.PhotoFilter(Color.WHITE, density = 0f),
            Adjustment.ColorLookup(ir.pixellab.core.model.AssetId("absent")),
            Adjustment.SelectiveColor(),
            Adjustment.ChannelMixer(),
            // Measured, so the arithmetic actually runs: both scales at zero have to come back to
            // the original however far the source it measured happens to sit.
            Adjustment.MatchColor(
                statistics = ir.pixellab.core.model.ColorStatistics(
                    mean = Vec3(0.9f, 0.2f, 0.1f),
                    deviation = Vec3(0.4f, 0.05f, 0.2f),
                    measured = true,
                ),
                luminance = 0f,
                colorIntensity = 0f,
            ),
            Adjustment.ReplaceColor(),
            Adjustment.Equalize(),
        )
        val probes = listOf(red, grey, Vec3(0.2f, 0.6f, 0.9f), Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f))

        for (adjustment in resting) {
            for (probe in probes) {
                val out = at(probe, adjustment)
                val drift = maxOf(abs(out.x - probe.x), abs(out.y - probe.y), abs(out.z - probe.z))
                check(drift < TOLERANCE) {
                    "${adjustment::class.simpleName} moved $probe to $out"
                }
            }
        }
    }

    // ---- the ones with a documented Photoshop behaviour --------------------------------------

    @Test
    fun `desaturate uses HSL lightness and not luma`() {
        // Photoshop's Desaturate is Hue/Saturation at -100, so pure red becomes *mid* grey: the
        // midpoint of the brightest and darkest channel. A luma conversion gives 0.21, and reaching
        // for the luma helper is the obvious mistake — invisible until two files are compared.
        val out = at(red, Adjustment.Desaturate)
        abs(out.x - 0.5f) shouldBeLessThan TOLERANCE
        out.x shouldBe out.y
        out.y shouldBe out.z
    }

    @Test
    fun `desaturate and hue-saturation at minus one hundred agree`() {
        // The two are the same operation in Photoshop, so they have to be the same operation here.
        for (probe in listOf(red, Vec3(0.2f, 0.6f, 0.9f), Vec3(0.9f, 0.7f, 0.1f))) {
            val desaturated = at(probe, Adjustment.Desaturate)
            val zeroed = at(probe, Adjustment.HueSaturation(saturation = -1f))
            abs(desaturated.x - zeroed.x) shouldBeLessThan TOLERANCE
        }
    }

    // ---- HSL: the range, which is the whole reason a colour panel is usable ------------------

    /** Saturated probes on the six family centres, so a band test is not fighting the grey guard. */
    private val skyBlue = Vec3(0.1f, 0.35f, 0.9f)
    private val skinRed = Vec3(0.9f, 0.45f, 0.35f)
    private val leafGreen = Vec3(0.2f, 0.75f, 0.2f)

    @Test
    fun `a family moves only its own hues`() {
        // The property the whole control exists for: deepen a sky without turning skin cyan. With
        // master alone this is impossible at any slider setting, which is why every serious editor
        // has the range and why one without it feels blunt however many sliders it has.
        val blues = Adjustment.HueSaturation(saturation = -1f, range = ColorFamily.BLUES)
        val sky = at(skyBlue, blues)
        val skin = at(skinRed, blues)

        // The sky is drained…
        abs(sky.x - sky.z) shouldBeLessThan 0.1f
        // …and the skin is untouched, to the last digit.
        abs(skin.x - skinRed.x) shouldBeLessThan TOLERANCE
        abs(skin.z - skinRed.z) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `master still moves everything, so the default did not change`() {
        val master = Adjustment.HueSaturation(saturation = -1f)
        for (probe in listOf(skyBlue, skinRed, leafGreen)) {
            val out = at(probe, master)
            abs(out.x - out.y) shouldBeLessThan 0.02f
            abs(out.y - out.z) shouldBeLessThan 0.02f
        }
    }

    @Test
    fun `the band is soft at its edge, so no visible boundary appears in a gradient`() {
        // A hard band would put a line across every sky that runs from cyan to blue. The weight has
        // to fall off, and it has to fall off monotonically — the second is what a smooth ramp buys
        // over a plain threshold.
        val centre = Adjustment.HueSaturation(range = ColorFamily.BLUES).rangeCentre!!
        var previous = 1f
        var sawPartial = false
        for (step in 0..24) {
            val hue = centre + step * (0.5f / 24f)
            val w = Adjust.hueBandWeight(hue, 1f, centre)
            (w <= previous + TOLERANCE) shouldBe true
            if (w > 0.01f && w < 0.99f) sawPartial = true
            previous = w
        }
        // There is genuinely a ramp rather than a step.
        sawPartial shouldBe true
    }

    @Test
    fun `the band wraps, so reds are not cut in half at the seam`() {
        // Zero and one are the same hue. A band measured by plain subtraction reaches 10 degrees and
        // not 350, and the reds family would cover only its warm half.
        val warm = Adjust.hueBandWeight(0.02f, 1f, 0f)
        val cool = Adjust.hueBandWeight(0.98f, 1f, 0f)
        abs(warm - cool) shouldBeLessThan TOLERANCE
        warm shouldBeGreaterThan 0.9f
    }

    @Test
    fun `a near-grey pixel is left out of a family`() {
        // Otherwise rotating "the reds" swings every neutral whose noise happened to lean warm, and
        // a smooth wall comes back mottled.
        val nearGrey = Vec3(0.52f, 0.5f, 0.5f)
        val reds = Adjustment.HueSaturation(hue = 0.25f, range = ColorFamily.REDS)
        val out = at(nearGrey, reds)
        abs(out.x - nearGrey.x) shouldBeLessThan 0.02f
        abs(out.y - nearGrey.y) shouldBeLessThan 0.02f
        abs(out.z - nearGrey.z) shouldBeLessThan 0.02f
    }

    @Test
    fun `the six families tile the wheel without a gap`() {
        // Thirty-degree cores sixty degrees apart with thirty-degree falloffs: every hue belongs to
        // at least one family at full weight or to two partially. A gap would be a colour no slider
        // in the panel could reach.
        val centres = HUE_FAMILY_ORDER.map { Adjustment.HueSaturation(range = it).rangeCentre!! }
        for (step in 0 until 72) {
            val hue = step / 72f
            val best = centres.maxOf { Adjust.hueBandWeight(hue, 1f, it) }
            best shouldBeGreaterThan 0.4f
        }
    }

    @Test
    fun `the non-hue families fall back to master rather than doing nothing`() {
        // Whites, neutrals and blacks are lightness bands and belong to selective colour. Treating
        // them as a hue centre would silently pin the adjustment to red.
        Adjustment.HueSaturation(range = ColorFamily.NEUTRALS).rangeCentre shouldBe null
        val out = at(skyBlue, Adjustment.HueSaturation(saturation = -1f, range = ColorFamily.WHITES))
        abs(out.x - out.z) shouldBeLessThan 0.02f
    }

    @Test
    fun `invert is its own inverse`() {
        val once = at(Vec3(0.2f, 0.6f, 0.9f), Adjustment.Invert)
        val twice = at(once, Adjustment.Invert)
        abs(twice.x - 0.2f) shouldBeLessThan TOLERANCE
        abs(twice.z - 0.9f) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `posterize lands on evenly spaced levels that reach both ends`() {
        // Four levels means the outputs are 0, 1/3, 2/3 and 1 — the ends included. A quantiser that
        // never reaches white is the usual off-by-one and it greys out every highlight.
        val seen = (0..100).map { at(Vec3(it / 100f, 0f, 0f), Adjustment.Posterize(4)).x }.distinct().sorted()
        seen.size shouldBe 4
        abs(seen.first()) shouldBeLessThan TOLERANCE
        abs(seen.last() - 1f) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `threshold is a hard split about its level`() {
        at(Vec3(0.9f, 0.9f, 0.9f), Adjustment.Threshold(0.5f)).x shouldBe 1f
        at(Vec3(0.1f, 0.1f, 0.1f), Adjustment.Threshold(0.5f)).x shouldBe 0f
    }

    @Test
    fun `vibrance moves a muted colour further than a saturated one`() {
        // The entire reason vibrance exists rather than a second saturation slider: it has to
        // protect what is already saturated, which is what keeps skin from going orange.
        val muted = Vec3(0.5f, 0.45f, 0.4f)
        val vivid = Vec3(0.9f, 0.1f, 0.05f)
        val adjustment = Adjustment.Vibrance(vibrance = 1f)
        fun spread(c: Vec3) = maxOf(c.x, c.y, c.z) - minOf(c.x, c.y, c.z)

        val mutedGain = spread(at(muted, adjustment)) / spread(muted)
        val vividGain = spread(at(vivid, adjustment)) / spread(vivid)
        mutedGain shouldBeGreaterThan vividGain
    }

    @Test
    fun `black and white tells two colours of equal luminance apart`() {
        // Three weights cannot do this at all, and it is the reason the panel has six. A red and a
        // green chosen to share a luma have to separate once the red slider moves.
        val redish = Vec3(0.8f, 0.2f, 0.2f)
        val greenish = Vec3(0.2f, 0.8f, 0.2f)
        val lifted = Adjustment.BlackWhite(listOf(1.5f, 0.6f, 0.2f, 0.6f, 0.2f, 0.8f))
        at(redish, lifted).x shouldBeGreaterThan at(greenish, lifted).x
    }

    @Test
    fun `colour balance preserves luminosity when asked`() {
        val probe = Vec3(0.4f, 0.5f, 0.6f)
        val warmed = Adjustment.ColorBalance(midtones = Vec3(0.2f, 0f, -0.2f), preserveLuminosity = true)
        val out = at(probe, warmed)
        abs(Adjust.luma(out.x, out.y, out.z) - Adjust.luma(probe.x, probe.y, probe.z)) shouldBeLessThan 0.01f
    }

    @Test
    fun `selective color relative cannot invent ink that is not there`() {
        // The property that makes Relative the safe mode on skin, and the one thing that separates
        // it from Absolute. A pure red holds no cyan, so a cyan push must leave it exactly alone.
        val cyanPush = listOf(ColorRange(ColorFamily.REDS, cyan = 0.5f))
        val relative = at(red, Adjustment.SelectiveColor(ranges = cyanPush, absolute = false))
        val absolute = at(red, Adjustment.SelectiveColor(ranges = cyanPush, absolute = true))

        abs(relative.x - red.x) shouldBeLessThan TOLERANCE
        absolute.x shouldBeLessThan red.x - 0.1f
    }

    @Test
    fun `channel mixer in monochrome uses one recipe for all three rows`() {
        val out = at(
            Vec3(0.8f, 0.4f, 0.2f),
            Adjustment.ChannelMixer(
                monochrome = true,
                gray = ir.pixellab.core.model.ChannelRecipe(red = 1f),
            ),
        )
        abs(out.x - 0.8f) shouldBeLessThan TOLERANCE
        out.x shouldBe out.y
        out.y shouldBe out.z
    }

    @Test
    fun `exposure of one stop doubles a mid tone`() {
        val out = at(Vec3(0.25f, 0.25f, 0.25f), Adjustment.Exposure(exposure = 1f))
        abs(out.x - 0.5f) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `levels gamma lifts the midtone without moving the ends`() {
        val lifted = Adjustment.Levels(gamma = 2f)
        abs(at(Vec3(0f, 0f, 0f), lifted).x) shouldBeLessThan TOLERANCE
        abs(at(Vec3(1f, 1f, 1f), lifted).x - 1f) shouldBeLessThan TOLERANCE
        at(grey, lifted).x shouldBeGreaterThan 0.6f
    }

    @Test
    fun `replace color leaves colours outside its fuzziness alone`() {
        // The whole point of the panel: it selects by distance from a picked colour, so a blue must
        // survive a replacement aimed at red however hard the hue is pushed.
        val replaceRed = Adjustment.ReplaceColor(
            target = Color(1f, 0f, 0f), fuzziness = 0.15f, hue = 0.5f,
        )
        val blue = Vec3(0f, 0f, 1f)
        val out = at(blue, replaceRed)
        abs(out.z - 1f) shouldBeLessThan TOLERANCE

        val hit = at(Vec3(0.95f, 0.05f, 0.05f), replaceRed)
        hit.z shouldBeGreaterThan 0.5f
    }

    // ---- the whole-image ones ----------------------------------------------------------------

    @Test
    fun `equalize spreads a compressed range back across the whole scale`() {
        // A picture living entirely between 0.4 and 0.6 has to come back using the full range,
        // because that is what equalisation is for.
        val compressed = Raster(64, 1, 3)
        for (i in 0 until 64) {
            val v = 0.4f + 0.2f * i / 63f
            compressed.data[i * 3] = v
            compressed.data[i * 3 + 1] = v
            compressed.data[i * 3 + 2] = v
        }
        val table = Adjust.equalizeTable(compressed)
        val out = Adjust.apply(compressed, Adjustment.Equalize(table.toList()))

        val values = (0 until 64).map { out.data[it * 3] }
        values.max() shouldBeGreaterThan 0.95f
        values.min() shouldBeLessThan 0.1f
        // Monotone: equalisation redistributes tones, it never reorders them.
        for (i in 1 until values.size) check(values[i] >= values[i - 1] - TOLERANCE) {
            "equalise reordered tones at $i: ${values[i - 1]} then ${values[i]}"
        }
    }

    @Test
    fun `equalize with no measurement is the identity`() {
        // A layer that has just been added has not been measured yet, and it must show the picture
        // unchanged rather than black.
        val out = at(Vec3(0.3f, 0.6f, 0.9f), Adjustment.Equalize())
        abs(out.y - 0.6f) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `match color moves the mean towards the source it measured`() {
        val dark = swatch(Vec3(0.2f, 0.2f, 0.25f), Vec3(0.25f, 0.2f, 0.3f), Vec3(0.15f, 0.2f, 0.2f))
        val bright = swatch(Vec3(0.7f, 0.7f, 0.6f), Vec3(0.75f, 0.7f, 0.65f), Vec3(0.65f, 0.7f, 0.6f))

        val stats = Adjust.statistics(bright)
        val matched = Adjust.apply(dark, Adjustment.MatchColor(statistics = stats))
        val before = Adjust.statistics(dark).mean
        val after = Adjust.statistics(matched).mean

        Adjust.luma(after.x, after.y, after.z) shouldBeGreaterThan Adjust.luma(before.x, before.y, before.z)
    }

    @Test
    fun `match color at full fade returns the original`() {
        val dark = swatch(Vec3(0.2f, 0.2f, 0.25f), Vec3(0.25f, 0.2f, 0.3f))
        val bright = swatch(Vec3(0.7f, 0.7f, 0.6f), Vec3(0.75f, 0.7f, 0.65f))
        val faded = Adjust.apply(
            dark,
            Adjustment.MatchColor(statistics = Adjust.statistics(bright), fade = 1f),
        )
        abs(faded.data[0] - dark.data[0]) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `shadows and highlights lift a dark region without lifting a dark edge in a bright one`() {
        // The property a curve cannot have, and the only reason this adjustment exists. Both probes
        // are the same dark value; only their neighbourhoods differ.
        val width = 96
        val image = Raster(width, 1, 3)
        for (x in 0 until width) {
            // Left half dark, right half bright with one dark pixel in the middle of it.
            val v = if (x < width / 2) 0.12f else if (x == width * 3 / 4) 0.12f else 0.85f
            image.data[x * 3] = v
            image.data[x * 3 + 1] = v
            image.data[x * 3 + 2] = v
        }
        val out = Adjust.shadowsHighlights(
            image,
            Adjustment.ShadowsHighlights(shadowAmount = 1f, shadowRadius = 12f, highlightAmount = 0f),
        )

        val inDarkRegion = out.data[(width / 4) * 3]
        val darkEdgeInBright = out.data[(width * 3 / 4) * 3]
        inDarkRegion shouldBeGreaterThan 0.25f
        darkEdgeInBright shouldBeLessThan inDarkRegion
    }

    @Test
    fun `hdr local adaptation compresses the range and keeps the detail`() {
        // A steep ramp with fine texture on it. Tone mapping has to bring the ends together while
        // leaving the texture measurable — a global curve does the first and destroys the second.
        val width = 128
        val scene = Raster(width, 1, 3)
        for (x in 0 until width) {
            // Kept clear of both ends so that "range" measures the operator rather than the clamp.
            val ramp = 0.08f + 0.84f * x / (width - 1f)
            val texture = if (x % 4 == 0) 0.03f else -0.03f
            val v = (ramp + texture).coerceIn(0f, 1f)
            scene.data[x * 3] = v
            scene.data[x * 3 + 1] = v
            scene.data[x * 3 + 2] = v
        }

        val mapped = Adjust.hdrToning(
            scene,
            Adjustment.HdrToning(method = HdrMethod.LOCAL_ADAPTATION, strength = 2f, radius = 16f),
        )

        fun range(r: Raster) = (0 until width).map { r.data[it * 3] }.let { it.max() - it.min() }
        fun texture(r: Raster) = (4 until width - 4).sumOf {
            abs(r.data[it * 3] - r.data[(it - 1) * 3]).toDouble()
        }.toFloat()

        range(mapped) shouldBeLessThan range(scene)
        // Local contrast survives: the frame-to-frame variation is not flattened along with it.
        texture(mapped) shouldBeGreaterThan texture(scene) * 0.5f
    }

    @Test
    fun `clip points sit where the fractions say`() {
        val image = Raster(100, 1, 3)
        for (x in 0 until 100) {
            val v = x / 99f
            image.data[x * 3] = v
            image.data[x * 3 + 1] = v
            image.data[x * 3 + 2] = v
        }
        val (black, white) = Adjust.clipPoints(image, blackClip = 0.1f, whiteClip = 0.1f)
        abs(black - 0.1f) shouldBeLessThan 0.03f
        abs(white - 0.9f) shouldBeLessThan 0.03f
    }

    @Test
    fun `alpha is carried through untouched`() {
        // An adjustment changes colour, never coverage. Correcting the alpha channel along with the
        // others eats the soft edge of every mask in the document.
        val image = Raster(2, 1, 4)
        image.data[3] = 0.25f
        image.data[7] = 0.75f
        val out = Adjust.apply(image, Adjustment.Invert)
        out.data[3] shouldBe 0.25f
        out.data[7] shouldBe 0.75f
    }

    /** Eight bits is a step of 0.004; anything below half of that cannot show on screen. */
    private companion object {
        const val TOLERANCE = 0.002f
    }
}
