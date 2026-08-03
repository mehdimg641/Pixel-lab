package ir.pixellab.core.mesh

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec3
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `LightRig.environment` was declared in wave 14 and read by nothing until now. These check the
 * thing that makes it worth having: what a mirror reflects actually comes from the picture.
 */
class EnvironmentTest {

    /** Half red, half blue, split down the middle of the longitude range. */
    private fun twoTone(width: Int = 64, height: Int = 32): IntArray =
        IntArray(width * height) { i ->
            if ((i % width) < width / 2) (0xFF shl 24) or 0xFF0000 else (0xFF shl 24) or 0x0000FF
        }

    @Test
    fun `a direction reflects the part of the picture it points at`() {
        val map = LatLong(twoTone(), 64, 32)
        // Probed at the *middle* of each half, not at ±x. Those two directions land exactly on the
        // boundary between the halves, where a bilinear sample correctly returns the blend of both
        // — so a test placed there reads purple twice and says nothing about the mapping.
        val east = map.sample(Vec3(0f, 0f, 1f), 0f)
        val west = map.sample(Vec3(0f, 0f, -1f), 0f)
        val eastIsRed = east.x > east.z
        val westIsRed = west.x > west.z
        eastIsRed shouldBe !westIsRed
    }

    @Test
    fun `rotating the world moves the reflection rather than the letter`() {
        // Placing a highlight on a curve is done by turning the environment, which is the only way
        // to do it without moving the letter off the layout.
        val still = LatLong(twoTone(), 64, 32)
        val turned = LatLong(twoTone(), 64, 32, rotation = 180f)
        val direction = Vec3(0f, 0f, 1f)
        val a = still.sample(direction, 0f)
        val b = turned.sample(direction, 0f)
        (a.x > a.z) shouldBe !(b.x > b.z)
    }

    @Test
    fun `intensity is an exposure, so it scales what comes back`() {
        val dim = LatLong(twoTone(), 64, 32, intensity = 1f)
        val bright = LatLong(twoTone(), 64, 32, intensity = 3f)
        val direction = Vec3(1f, 0f, 0f)
        val a = dim.sample(direction, 0f)
        val b = bright.sample(direction, 0f)
        abs(b.x - a.x * 3f) shouldBeLessThan 0.001f
    }

    @Test
    fun `roughness blurs towards the average, so a rough metal loses the split`() {
        // The property a prefiltered map exists for. A mirror sees one half of the picture; a rough
        // surface sees the mean of both, and if roughness did nothing the two would be identical.
        val map = LatLong(twoTone(), 64, 32)
        val direction = Vec3(0f, 0f, 1f)
        val mirror = map.sample(direction, 0f)
        val rough = map.sample(direction, 1f)

        fun split(v: Vec3) = abs(v.x - v.z)
        split(rough) shouldBeLessThan split(mirror)
        // And the rough one is genuinely mixed rather than merely dimmed.
        (rough.x > 0.01f && rough.z > 0.01f) shouldBe true
    }

    @Test
    fun `the map is sampled in linear light`() {
        // An environment is a measurement of radiance and gets multiplied by a BRDF. Reflecting the
        // stored bytes instead makes a bright sky come back darker than it is, which is exactly the
        // difference between chrome and grey plastic.
        val midGrey = IntArray(64 * 32) { (0xFF shl 24) or 0x808080 }
        val map = LatLong(midGrey, 64, 32)
        val v = map.sample(Vec3(1f, 0f, 0f), 0f)
        // sRGB 128 is about 0.216 in linear light, not 0.502.
        v.x shouldBeLessThan 0.3f
        v.x shouldBeGreaterThan 0.15f
    }

    @Test
    fun `longitude wraps, so there is no seam down the back of a reflection`() {
        // Clamping instead of wrapping puts a visible join where the picture's two edges meet, and
        // on a curved bevel that join sweeps across the letter as it turns.
        val map = LatLong(twoTone(), 64, 32)
        val justBefore = map.sample(Vec3(-1f, 0f, -0.001f), 0f)
        val justAfter = map.sample(Vec3(-1f, 0f, 0.001f), 0f)
        abs(justBefore.x - justAfter.x) shouldBeLessThan 0.2f
    }

    @Test
    fun `the studio is still what a document with no environment gets`() {
        // The generated one is the default and the right default: the app ships no assets, and it is
        // the reason a gold letter looks like gold on a first launch.
        val up = Studio.sample(Vec3(0f, 1f, 0f), 0f)
        val down = Studio.sample(Vec3(0f, -1f, 0f), 0f)
        // Bright ceiling, dark floor — the sweep between them is what the eye reads as a mirror.
        (up.x + up.y + up.z) shouldBeGreaterThan (down.x + down.y + down.z)
    }

    @Test
    fun `a malformed environment is rejected rather than read past its end`() {
        try {
            LatLong(IntArray(4), 64, 32)
            error("a short pixel array was accepted")
        } catch (expected: IllegalArgumentException) {
            (expected.message?.contains("64x32") == true) shouldBe true
        }
    }
}
