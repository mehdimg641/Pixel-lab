package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.with
import ir.pixellab.core.render.Shaders
import ir.pixellab.core.render.TextureHandle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what reached the driver, so caching and pass order are assertable without a GPU. */
private class RecordingDevice : GlDeviceRecorder()

/** Split out so the recorder can be reused; Robolectric supplies the real Skia underneath. */
private open class GlDeviceRecorder : ir.pixellab.core.render.GlDevice {
    override val maxTextureSize = 4096
    var created = 0
    var deleted = 0
    val uploads = ArrayList<TextureHandle>()
    val programs = ArrayList<String>()
    private var next = 1

    override fun createTexture(width: Int, height: Int, bytesPerPixel: Int): TextureHandle {
        created++
        return TextureHandle(next++)
    }

    override fun uploadArgb(handle: TextureHandle, width: Int, height: Int, pixels: IntArray) {
        uploads += handle
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

    override fun useProgram(shaderId: String): Boolean {
        programs += shaderId
        return true
    }

    override fun bindInput(samplerName: String, handle: TextureHandle) = Unit
    override fun setFloat(name: String, value: Float) = Unit
    override fun setVec2(name: String, x: Float, y: Float) = Unit
    override fun setInt(name: String, value: Int) = Unit
    override fun draw(instances: Int) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentRendererTest {

    private val device = RecordingDevice()
    private val renderer = DocumentRenderer(device)

    private fun box(id: String, at: Vec2 = Vec2.ZERO, style: Style = Style.PLAIN_BLACK) = Layer.Shape(
        id = LayerId(id),
        geometry = ShapeGeometry.Rectangle(Vec2(200f, 100f)),
        transform = Transform(translation = at),
        style = style,
    )

    private fun document(vararg layers: Layer) = Document(
        id = DocumentId("d"),
        canvas = CanvasSpec(512, 512),
        layers = layers.toList(),
    )

    @Test
    fun `a plain layer runs its fill pass`() {
        renderer.render(document(box("a")))
        device.programs shouldBe listOf(Shaders.FILL.id)
        renderer.lastErrors shouldBe emptyList()
    }

    @Test
    fun `a styled layer runs the whole chain in photoshop's order`() {
        val style = Style(
            effects = listOf(Effect.DropShadow(blur = 10f), Effect.Stroke(4f, Fill.Solid(Color.WHITE))),
        )
        renderer.render(document(box("a", style = style)))
        val order = device.programs
        (order.indexOf(Shaders.SHADOW.id) < order.indexOf(Shaders.FILL.id)) shouldBe true
        (order.indexOf(Shaders.FILL.id) < order.indexOf(Shaders.STROKE.id)) shouldBe true
        renderer.lastErrors shouldBe emptyList()
    }

    @Test
    fun `moving a layer does not re-rasterise it`() {
        val document = document(box("a"))
        renderer.render(document)
        val afterFirst = device.uploads.size

        // A drag changes where the texture is sampled, never what is in it. Re-rasterising here is
        // what makes a gesture stutter — on a text layer it re-shapes the paragraph every frame.
        repeat(60) { frame ->
            renderer.render(document.mapLayer(LayerId("a")) { it.with(transform = Transform(Vec2(frame.toFloat(), 0f))) })
        }
        device.uploads.size shouldBe afterFirst
    }

    @Test
    fun `changing a layer's content does re-rasterise it`() {
        val document = document(box("a"))
        renderer.render(document)
        val afterFirst = device.uploads.size
        renderer.invalidate(LayerId("a"))
        renderer.render(document)
        (device.uploads.size > afterFirst) shouldBe true
    }

    @Test
    fun `a change of bleed re-rasterises because the texture changed shape`() {
        renderer.render(document(box("a")))
        val afterFirst = device.uploads.size
        // A wider stroke needs a bigger texture; reusing the old one would clip the effect.
        renderer.render(document(box("a", style = Style(effects = listOf(Effect.OuterGlow(blur = 40f))))))
        (device.uploads.size > afterFirst) shouldBe true
    }

    @Test
    fun `a hidden or fully transparent layer costs nothing`() {
        renderer.render(document(box("a").with(visible = false), box("b").with(opacity = 0f)))
        device.programs shouldBe emptyList()
    }

    @Test
    fun `bypassing effects renders only the fill`() {
        val style = Style(effects = listOf(Effect.DropShadow(blur = 10f), Effect.Bevel()))
        renderer.render(document(box("a", style = style)), effectsBypassed = true)
        // The `fx` badge has to be instant and reversible, so it drops the effects at plan time
        // rather than editing the document.
        device.programs shouldBe listOf(Shaders.FILL.id)
    }

    @Test
    fun `a pass-through group renders its children`() {
        val group = Layer.Group(id = LayerId("g"), children = listOf(box("a"), box("b")))
        renderer.render(document(group))
        device.programs shouldBe listOf(Shaders.FILL.id, Shaders.FILL.id)
    }

    @Test
    fun `every effect in a full style finds the textures it needs`() {
        val style = Style(
            effects = listOf(
                Effect.DropShadow(blur = 8f), Effect.OuterGlow(blur = 6f), Effect.Bevel(),
                Effect.Stroke(3f, Fill.Solid(Color.WHITE)), Effect.Satin(), Effect.InnerShadow(),
                Effect.InnerGlow(), Effect.Extrude(steps = 6), Effect.Reflection(),
                Effect.ChromaticOffset(), Effect.Noise(), Effect.EdgeRoughen(),
            ),
        )
        renderer.render(document(box("a", style = style)))
        // A missing fill or curve table reads as an effect that ran and did nothing, so it has to
        // surface here rather than on a device.
        renderer.lastErrors shouldBe emptyList()
    }

    @Test
    fun `layers composite bottom up, matching the document order`() {
        renderer.render(
            document(
                box("bottom", style = Style(effects = listOf(Effect.Stroke(2f, Fill.Solid(Color.WHITE))))),
                box("top"),
            ),
        )
        val order = device.programs
        // The stroke belongs to the lower layer, so everything it issues has to precede the upper
        // layer's fill.
        (order.indexOf(Shaders.STROKE.id) < order.lastIndexOf(Shaders.FILL.id)) shouldBe true
    }

    @Test
    fun `the memory estimate grows with the export scale`() {
        val document = document(box("a", style = Style(effects = listOf(Effect.DropShadow(blur = 30f)))))
        val screen = renderer.estimateBytes(document, scale = 1f)
        val export = renderer.estimateBytes(document, scale = 4f)
        (export > screen) shouldBe true
    }

    @Test
    fun `disposing frees every texture it allocated`() {
        renderer.render(document(box("a", style = Style(effects = listOf(Effect.Bevel())))))
        renderer.dispose()
        device.deleted shouldBe device.created
    }
}
