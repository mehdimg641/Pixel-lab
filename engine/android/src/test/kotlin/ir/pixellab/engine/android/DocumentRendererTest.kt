package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.model.BlendMode
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
        passes += Pass(shaderId)
        return true
    }

    override fun bindInput(samplerName: String, handle: TextureHandle) {
        passes.lastOrNull()?.inputs?.put(samplerName, handle)
    }

    override fun setFloat(name: String, value: Float) {
        passes.lastOrNull()?.floats?.put(name, value)
    }

    override fun setVec2(name: String, x: Float, y: Float) = Unit
    override fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) = Unit

    override fun setInt(name: String, value: Int) {
        passes.lastOrNull()?.ints?.put(name, value)
    }

    override fun setMat3(name: String, values: FloatArray) {
        passes.lastOrNull()?.matrices?.put(name, values.copyOf())
    }

    override fun draw(instances: Int) = Unit

    class Pass(val shader: String) {
        val inputs = LinkedHashMap<String, TextureHandle>()
        val floats = LinkedHashMap<String, Float>()
        val ints = LinkedHashMap<String, Int>()
        val matrices = LinkedHashMap<String, FloatArray>()
    }

    val passes = ArrayList<Pass>()
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
    fun `a plain layer runs its fill pass and is then composited onto the canvas`() {
        renderer.render(document(box("a")))
        // The composite is what was missing while every other pass worked: a layer rendered
        // correctly into a buffer that was then discarded looks exactly like a renderer doing
        // nothing at all.
        device.programs shouldBe listOf(Shaders.FILL.id, Shaders.COMPOSITE.id, Shaders.PRESENT.id)
        renderer.lastErrors shouldBe emptyList()
    }

    @Test
    fun `every visible layer reaches the canvas`() {
        renderer.render(document(box("a"), box("b"), box("c")))
        device.programs.count { it == Shaders.COMPOSITE.id } shouldBe 3
    }

    @Test
    fun `the finished canvas is presented exactly once`() {
        renderer.render(document(box("a"), box("b")))
        // Presenting per layer would apply the camera repeatedly and show the last layer alone.
        device.programs.count { it == Shaders.PRESENT.id } shouldBe 1
    }

    @Test
    fun `a layer's blend mode and opacity reach the composite`() {
        val layer = box("a").with(blendMode = BlendMode.HARD_LIGHT, opacity = 0.4f)
        renderer.render(document(layer))
        val composite = device.passes.last { it.shader == Shaders.COMPOSITE.id }
        // These are the three things a layer has that its effects do not; dropping them is silent
        // because the layer still appears, just wrong.
        composite.ints["uBlendMode"] shouldBe BlendMode.HARD_LIGHT.ordinal
        composite.floats["uOpacity"] shouldBe 0.4f
        (composite.matrices["uMap"] != null) shouldBe true
    }

    @Test
    fun `the camera is applied once at present time, not per layer`() {
        val viewport = Viewport(offset = Vec2(10f, 20f), zoom = 2f, screenSize = Vec2(800f, 600f))
        renderer.render(document(box("a"), box("b")), viewport = viewport)
        val present = device.passes.single { it.shader == Shaders.PRESENT.id }
        (present.matrices["uMap"] != null) shouldBe true
        // Rendering every layer through the camera would re-run each effect stack on every pan.
        device.programs.count { it == Shaders.PRESENT.id } shouldBe 1
    }

    @Test
    fun `a glass layer is handed the canvas as its backdrop`() {
        renderer.render(
            document(box("a"), box("b", style = Style(effects = listOf(Effect.BackdropBlur())))),
        )
        // Frosted glass reads what is already composited beneath it; without a backdrop the
        // executor reports it rather than rendering an opaque rectangle.
        renderer.lastErrors shouldBe emptyList()
        device.programs.contains(Shaders.BACKDROP_BLUR.id) shouldBe true
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
        // No effect passes and no composites — but the canvas is still presented, because an empty
        // document has to show the surround rather than the previous frame.
        device.programs shouldBe listOf(Shaders.PRESENT.id)
    }

    @Test
    fun `bypassing effects renders only the fill`() {
        val style = Style(effects = listOf(Effect.DropShadow(blur = 10f), Effect.Bevel()))
        renderer.render(document(box("a", style = style)), effectsBypassed = true)
        // The `fx` badge has to be instant and reversible, so it drops the effects at plan time
        // rather than editing the document.
        device.programs shouldBe listOf(Shaders.FILL.id, Shaders.COMPOSITE.id, Shaders.PRESENT.id)
    }

    @Test
    fun `a pass-through group renders its children`() {
        val group = Layer.Group(id = LayerId("g"), children = listOf(box("a"), box("b")))
        renderer.render(document(group))
        device.programs.count { it == Shaders.FILL.id } shouldBe 2
        device.programs.count { it == Shaders.COMPOSITE.id } shouldBe 2
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
