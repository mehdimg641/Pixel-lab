package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.render.GlDevice
import ir.pixellab.core.render.TextureHandle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Counts allocations and uploads; the rasterising underneath is real Skia. */
private class CountingDevice : GlDevice {
    override val maxTextureSize = 4096
    var created = 0
    var deleted = 0
    val uploaded = ArrayList<Triple<Int, Int, Int>>()
    private var next = 1

    override fun createTexture(
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        filter: ir.pixellab.core.render.TextureFilter,
    ): TextureHandle {
        created++
        return TextureHandle(next++)
    }

    override fun uploadArgb(handle: TextureHandle, width: Int, height: Int, pixels: IntArray) {
        uploaded += Triple(handle.id, width, height)
    }

    override fun uploadFloats(
        handle: TextureHandle,
        width: Int,
        height: Int,
        channels: Int,
        values: FloatArray,
    ) = Unit

    override fun deleteTexture(handle: TextureHandle) {
        deleted++
    }

    override fun bindTarget(handle: TextureHandle?) = Unit
    override fun clearTarget() = Unit
    override fun setBlend(enabled: Boolean) = Unit
    override fun useProgram(shaderId: String) = true
    override fun bindInput(samplerName: String, handle: TextureHandle) = Unit
    override fun setFloat(name: String, value: Float) = Unit
    override fun setVec2(name: String, x: Float, y: Float) = Unit
    override fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) = Unit
    override fun setInt(name: String, value: Int) = Unit
    override fun setMat3(name: String, values: FloatArray) = Unit
    override fun draw(instances: Int) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EffectTexturesTest {

    private val device = CountingDevice()
    private val textures = EffectTextures(device)

    private fun stroke(color: Color) = Effect.Stroke(4f, Fill.Solid(color))

    @Test
    fun `a stroke gets its own fill`() {
        val handle = textures.textureFor(stroke(Color.WHITE), "uFill")
        handle shouldBe TextureHandle(1)
        device.uploaded.single().second shouldBe 256
    }

    @Test
    fun `the same fill is uploaded once however many effects ask for it`() {
        // A bevel asks for its curve tables on every pass and a ten-shadow stack asks for a fill per
        // shadow; rebuilding and re-uploading each frame costs more than the effects themselves.
        repeat(30) { textures.textureFor(stroke(Color.WHITE), "uFill") }
        device.created shouldBe 1
    }

    @Test
    fun `two effects sharing a colour share one texture`() {
        val a = textures.textureFor(stroke(Color.WHITE), "uFill")
        val b = textures.textureFor(Effect.Overlay(Fill.Solid(Color.WHITE)), "uFill")
        // Keyed by the value, not by the effect — a style with six white strokes is one texture.
        a shouldBe b
        device.created shouldBe 1
    }

    @Test
    fun `a different colour gets a different texture`() {
        val a = textures.textureFor(stroke(Color.WHITE), "uFill")
        val b = textures.textureFor(stroke(Color.BLACK), "uFill")
        (a != b) shouldBe true
        device.created shouldBe 2
    }

    @Test
    fun `a shadow's colour becomes a fill even though it is stored as a colour`() {
        textures.textureFor(Effect.DropShadow(color = Color(0.1f, 0.2f, 0.3f)), "uFill")
            .shouldBeHandle()
    }

    @Test
    fun `a bevel's two curves are separate one-pixel tables`() {
        val bevel = Effect.Bevel(profile = Curve.ROUNDED, glossContour = Curve.CHAMFER)
        val profile = textures.textureFor(bevel, "uProfile")
        val gloss = textures.textureFor(bevel, "uGloss")
        (profile != gloss) shouldBe true
        device.uploaded.all { it.third == 1 } shouldBe true
        device.uploaded.all { it.second == 256 } shouldBe true
    }

    @Test
    fun `two effects using the same curve share its table`() {
        textures.textureFor(Effect.Bevel(profile = Curve.LINEAR), "uProfile")
        textures.textureFor(Effect.Bevel(profile = Curve.LINEAR, size = 40f), "uProfile")
        device.created shouldBe 1
    }

    @Test
    fun `an extrusion's near and far paints are distinct`() {
        val extrude = Effect.Extrude(
            nearFill = Fill.Solid(Color.WHITE),
            farFill = Fill.Solid(Color.BLACK),
        )
        val near = textures.textureFor(extrude, "uNearFill")
        val far = textures.textureFor(extrude, "uFarFill")
        // Sharing these would collapse the depth gradient the whole effect exists to produce.
        (near != far) shouldBe true
    }

    @Test
    fun `the layer's own fill pass carries no effect and still gets a texture`() {
        textures.layerFill = Fill.Solid(Color(0.9f, 0.1f, 0.1f))
        // The graph's fill pass has no effect attached, so the style's paint has to arrive here.
        textures.textureFor(null, "uFill").shouldBeHandle()
    }

    @Test
    fun `a sampler nothing owns returns null rather than a wrong texture`() {
        // The executor turns this into a reported error; inventing a texture here would hide it.
        textures.textureFor(stroke(Color.WHITE), "uSdf") shouldBe null
        textures.textureFor(Effect.Noise(), "uFill") shouldBe null
    }

    @Test
    fun `invalidating a fill drops only that one`() {
        val fill = Fill.Solid(Color.WHITE)
        textures.textureFor(Effect.Overlay(fill), "uFill")
        textures.textureFor(stroke(Color.BLACK), "uFill")
        textures.invalidate(fill)
        device.deleted shouldBe 1
        // And the next request rebuilds it rather than handing back a deleted handle.
        textures.textureFor(Effect.Overlay(fill), "uFill")
        device.created shouldBe 3
    }

    @Test
    fun `disposing frees every cached texture`() {
        textures.textureFor(stroke(Color.WHITE), "uFill")
        textures.textureFor(stroke(Color.BLACK), "uFill")
        textures.textureFor(Effect.Bevel(), "uProfile")
        textures.dispose()
        device.deleted shouldBe 3
    }

    private fun TextureHandle?.shouldBeHandle() {
        (this != null) shouldBe true
    }
}
