package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.LayerMask
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.VectorMask
import ir.pixellab.core.model.with
import ir.pixellab.core.render.Shaders
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The compositing model: masks, clipping groups, group isolation, smart objects.
 *
 * All four are expressible in the document and were, until now, drawn as if they were not there —
 * the failure mode that makes an imported PSD look nothing like the file. Each is checked against
 * what Photoshop actually does rather than against what is easy to implement, because the two
 * differ in exactly the cases that matter: a clipping group whose base has a blend mode, and a
 * group at partial opacity whose children overlap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositingModelTest {

    private val device = MaskRecordingDevice()
    private val renderer = DocumentRenderer(device)

    private fun box(
        id: String,
        at: Vec2 = Vec2.ZERO,
        style: Style = Style.PLAIN_BLACK,
    ) = Layer.Shape(
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

    private val composites get() = device.passes.filter { it.shader == Shaders.COMPOSITE.id }

    // ---- masks -------------------------------------------------------------------------------

    @Test
    fun `a layer with no mask tells the shader so`() {
        renderer.render(document(box("a")))
        // The flags are what switch the sampling off. Leaving them set from the previous layer is
        // how one masked layer silently masks everything drawn after it.
        composites.single().ints["uMaskFlags"] shouldBe 0
    }

    @Test
    fun `every mask sampler is bound even when unused`() {
        renderer.render(document(box("a")))
        val pass = composites.single()
        // An unbound sampler in GL ES reads texture unit zero, which holds whatever the previous
        // draw left there — a layer would be masked by the last thing rendered.
        (pass.inputs["uMask"] != null) shouldBe true
        (pass.inputs["uVectorMask"] != null) shouldBe true
        (pass.inputs["uClip"] != null) shouldBe true
    }

    @Test
    fun `a raster mask is sampled and carries its density`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        renderer.render(
            document(box("a").with(mask = LayerMask(AssetId("m"), density = 0.5f))),
        )
        val pass = composites.single()
        (pass.ints.getValue("uMaskFlags") and 1) shouldBe 1
        pass.floats["uMaskDensity"] shouldBe 0.5f
    }

    @Test
    fun `an inverted mask says so rather than being inverted on the cpu`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        renderer.render(
            document(box("a").with(mask = LayerMask(AssetId("m"), inverted = true))),
        )
        // Inverting the pixels would mean a second copy of the mask per layer that shares it, and
        // the same mask is routinely shared.
        (composites.single().ints.getValue("uMaskFlags") and 2) shouldBe 2
    }

    @Test
    fun `a disabled mask is not sampled at all`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        renderer.render(document(box("a").with(mask = LayerMask(AssetId("m"), enabled = false))))
        composites.single().ints["uMaskFlags"] shouldBe 0
    }

    @Test
    fun `a mask at zero density is not sampled either`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        renderer.render(document(box("a").with(mask = LayerMask(AssetId("m"), density = 0f))))
        // Zero density is Photoshop's way of temporarily switching a mask off without losing it,
        // and sampling it anyway would cost a texture read per pixel for nothing.
        composites.single().ints["uMaskFlags"] shouldBe 0
    }

    @Test
    fun `a mask whose asset has not loaded degrades instead of hiding the layer`() {
        renderer.assets = AssetSource { null }
        renderer.render(document(box("a").with(mask = LayerMask(AssetId("missing")))))
        // A project opened before its assets are decoded must show the artwork, not a blank canvas.
        composites.single().ints["uMaskFlags"] shouldBe 0
    }

    @Test
    fun `an unlinked mask is sampled in canvas space`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        val moved = box("a", at = Vec2(120f, 60f))
        renderer.render(document(moved.with(mask = LayerMask(AssetId("m"), unlinked = true))))
        val map = composites.single().matrices.getValue("uMaskMap")
        // Identity: the mask stays pinned to the artboard while the layer slides underneath it,
        // which is the whole point of unlinking it.
        map.toList() shouldBe listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    @Test
    fun `a linked mask travels with the layer`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        val moved = box("a", at = Vec2(120f, 60f))
        renderer.render(document(moved.with(mask = LayerMask(AssetId("m")))))
        val map = composites.single().matrices.getValue("uMaskMap")
        (map.toList() != listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) shouldBe true
    }

    @Test
    fun `a vector mask is rasterised and sampled`() {
        renderer.render(
            document(box("a").with(vectorMask = VectorMask(ShapeGeometry.Ellipse(Vec2(180f, 90f))))),
        )
        (composites.single().ints.getValue("uMaskFlags") and 4) shouldBe 4
    }

    @Test
    fun `a raster mask and a vector mask apply together`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        renderer.render(
            document(
                box("a").with(
                    mask = LayerMask(AssetId("m")),
                    vectorMask = VectorMask(ShapeGeometry.Ellipse(Vec2(180f, 90f))),
                ),
            ),
        )
        // Photoshop multiplies them; taking only one is a difference the user sees immediately on
        // any layer that uses both to build a shape.
        val flags = composites.single().ints.getValue("uMaskFlags")
        (flags and 1) shouldBe 1
        (flags and 4) shouldBe 4
    }

    @Test
    fun `a mask texture is built once and reused across frames`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        val doc = document(box("a").with(mask = LayerMask(AssetId("m"))))
        renderer.render(doc)
        val afterFirst = device.created
        renderer.render(doc)
        // A mask is often the largest image in a project; uploading it sixty times a second would
        // cost more than everything the layer's effects do.
        (device.created - afterFirst < afterFirst) shouldBe true
    }

    // ---- clipping ----------------------------------------------------------------------------

    @Test
    fun `a clipped layer is told what it is clipped to`() {
        renderer.render(document(box("base"), box("over").with(clipped = true)))
        // Three composites: the base into the group, the follower onto it, the group onto the
        // canvas. The follower is the one carrying the clip flag.
        val clipped = composites.filter { (it.ints["uMaskFlags"] ?: 0) and 16 != 0 }
        clipped.size shouldBe 1
    }

    @Test
    fun `the clipping group is composited as one unit under the base's blend mode`() {
        renderer.render(
            document(
                box("base").with(blendMode = BlendMode.MULTIPLY, opacity = 0.5f),
                box("over").with(clipped = true),
            ),
        )
        val last = composites.last()
        // Photoshop's default is "blend clipped layers as group": the base's blend and opacity
        // govern the finished group, not the base's own pixels. Compositing each follower straight
        // onto the canvas instead would blend it with the document rather than with the base.
        last.ints["uBlendMode"] shouldBe BlendMode.MULTIPLY.ordinal
        last.floats["uOpacity"] shouldBe 0.5f
    }

    @Test
    fun `the base goes into the group at full strength`() {
        renderer.render(
            document(
                box("base").with(blendMode = BlendMode.MULTIPLY, opacity = 0.5f),
                box("over").with(clipped = true),
            ),
        )
        val first = composites.first()
        // The followers are clipped to the base's shape, which is not dimmed by the base's own
        // opacity; applying it twice would make a half-opaque base clip to a quarter.
        first.floats["uOpacity"] shouldBe 1f
        first.ints["uBlendMode"] shouldBe BlendMode.NORMAL.ordinal
    }

    @Test
    fun `hiding the base hides everything clipped to it`() {
        renderer.render(
            document(box("base").with(visible = false), box("over").with(clipped = true)),
        )
        // The followers have nothing to be clipped to. Drawing them unclipped is the bug that makes
        // hiding a shape reveal the photo that was inside it.
        composites.size shouldBe 0
    }

    @Test
    fun `a clipped layer at the bottom of the stack behaves as an ordinary layer`() {
        renderer.render(document(box("lonely").with(clipped = true), box("above")))
        // There is nothing below it to clip to, which is exactly how Photoshop treats it.
        composites.size shouldBe 2
        composites.none { (it.ints["uMaskFlags"] ?: 0) and 16 != 0 } shouldBe true
    }

    @Test
    fun `several layers clip to the same base`() {
        renderer.render(
            document(
                box("base"),
                box("one").with(clipped = true),
                box("two").with(clipped = true),
                box("unrelated"),
            ),
        )
        composites.count { (it.ints["uMaskFlags"] ?: 0) and 16 != 0 } shouldBe 2
    }

    // ---- groups ------------------------------------------------------------------------------

    @Test
    fun `a pass-through group costs no extra composite`() {
        renderer.render(document(Layer.Group(LayerId("g"), children = listOf(box("a"), box("b")))))
        // Pass-through is the absence of isolation, not a style: the children meet the document
        // directly, so a Multiply child multiplies with what is under the group.
        composites.size shouldBe 2
    }

    @Test
    fun `a group with a blend mode is flattened first`() {
        renderer.render(
            document(
                Layer.Group(
                    LayerId("g"),
                    children = listOf(box("a"), box("b")),
                    passThrough = false,
                    blendMode = BlendMode.SCREEN,
                ),
            ),
        )
        // Two children into the group's own buffer, then the buffer onto the canvas.
        composites.size shouldBe 3
        composites.last().ints["uBlendMode"] shouldBe BlendMode.SCREEN.ordinal
    }

    @Test
    fun `a group below full opacity is flattened first`() {
        renderer.render(
            document(
                Layer.Group(LayerId("g"), children = listOf(box("a"), box("b")), opacity = 0.5f),
            ),
        )
        // Applying the opacity to each child separately is the classic mistake: every overlap
        // between children shows through, which is not what the user asked for.
        composites.size shouldBe 3
        composites.last().floats["uOpacity"] shouldBe 0.5f
    }

    @Test
    fun `a masked group is flattened first`() {
        renderer.assets = AssetSource { RasterImage(4, 4, IntArray(16) { -1 }) }
        renderer.render(
            document(
                Layer.Group(
                    LayerId("g"),
                    children = listOf(box("a"), box("b")),
                    mask = LayerMask(AssetId("m")),
                ),
            ),
        )
        composites.size shouldBe 3
        (composites.last().ints.getValue("uMaskFlags") and 1) shouldBe 1
    }

    @Test
    fun `a group's effects apply to the flattened result, not to each child`() {
        renderer.render(
            document(
                Layer.Group(
                    LayerId("g"),
                    children = listOf(box("a"), box("b")),
                    style = Style(effects = listOf(Effect.DropShadow(blur = 12f))),
                ),
            ),
        )
        // One shadow cast by the group's combined silhouette. Two shadows would mean each child
        // casting its own, with the seam between them visible wherever they overlap.
        device.programs.count { it == Shaders.SHADOW.id } shouldBe 1
    }

    @Test
    fun `a hidden group draws nothing`() {
        renderer.render(
            document(Layer.Group(LayerId("g"), children = listOf(box("a")), visible = false)),
        )
        composites.size shouldBe 0
    }

    @Test
    fun `nested groups each get their own isolation`() {
        renderer.render(
            document(
                Layer.Group(
                    LayerId("outer"),
                    opacity = 0.5f,
                    children = listOf(
                        box("a"),
                        Layer.Group(LayerId("inner"), opacity = 0.5f, children = listOf(box("b"))),
                    ),
                ),
            ),
        )
        // b into inner, inner into outer, a into outer, outer onto the canvas.
        composites.size shouldBe 4
    }

    // ---- smart objects -----------------------------------------------------------------------

    @Test
    fun `an instance draws its source`() {
        renderer.render(
            document(box("source"), Layer.Instance(LayerId("copy"), source = LayerId("source"))),
        )
        // 125 of these in the reference PSDs; without this an imported file loses most of its
        // content and the layer panel disagrees with the canvas.
        composites.size shouldBe 2
    }

    @Test
    fun `an instance uses its own transform, not the source's`() {
        renderer.render(
            document(
                box("source", at = Vec2(10f, 10f)),
                Layer.Instance(
                    LayerId("copy"),
                    source = LayerId("source"),
                    transform = Transform(translation = Vec2(300f, 200f)),
                ),
            ),
        )
        val maps = composites.map { it.matrices.getValue("uMap").toList() }
        // Sharing the source's placement would stack every instance on the original, which is the
        // one arrangement that makes twenty-nine copies useless.
        (maps[0] != maps[1]) shouldBe true
    }

    @Test
    fun `an instance's own effects replace the source's`() {
        renderer.render(
            document(
                box("source", style = Style(effects = listOf(Effect.DropShadow(blur = 8f)))),
                Layer.Instance(
                    LayerId("copy"),
                    source = LayerId("source"),
                    style = Style(effects = listOf(Effect.Stroke(4f, Fill.Solid(Color.WHITE)))),
                ),
            ),
        )
        device.programs.count { it == Shaders.SHADOW.id } shouldBe 1
        device.programs.count { it == Shaders.STROKE.id } shouldBe 1
    }

    @Test
    fun `an instance with no effects of its own shows the source's`() {
        renderer.render(
            document(
                box("source", style = Style(effects = listOf(Effect.DropShadow(blur = 8f)))),
                Layer.Instance(LayerId("copy"), source = LayerId("source")),
            ),
        )
        device.programs.count { it == Shaders.SHADOW.id } shouldBe 2
    }

    @Test
    fun `instances of one shape share its rasterised silhouette`() {
        val aloneDevice = MaskRecordingDevice()
        DocumentRenderer(aloneDevice).render(document(box("source")))

        renderer.render(
            document(
                box("source"),
                Layer.Instance(LayerId("a"), source = LayerId("source")),
                Layer.Instance(LayerId("b"), source = LayerId("source")),
            ),
        )
        // The reason the reference files are built this way: one rasterisation between all of them,
        // so three instances cost exactly what the source alone costs. Copying the layer instead
        // would rasterise the same outline three times.
        composites.size shouldBe 3
        device.uploads.size shouldBe aloneDevice.uploads.size
    }

    @Test
    fun `an instance pointing at a layer that contains it is reported rather than looping`() {
        renderer.render(
            document(
                Layer.Group(
                    LayerId("g"),
                    children = listOf(box("a"), Layer.Instance(LayerId("i"), source = LayerId("g"))),
                ),
            ),
        )
        // Without the guard this recurses until the stack ends, which on a phone is a crash with no
        // useful report at all.
        (renderer.lastErrors.isNotEmpty()) shouldBe true
    }

    @Test
    fun `an instance with a missing source is reported`() {
        renderer.render(document(Layer.Instance(LayerId("i"), source = LayerId("gone"))))
        (renderer.lastErrors.isNotEmpty()) shouldBe true
    }

    // ---- placement ---------------------------------------------------------------------------

    @Test
    fun `a layer lands in the same place at every export scale`() {
        val doc = document(box("a", at = Vec2(100f, 50f)))
        renderer.render(doc, scale = 1f)
        val atOne = composites.last().matrices.getValue("uMap").toList()
        device.passes.clear()
        renderer.render(doc, scale = 4f)
        val atFour = composites.last().matrices.getValue("uMap").toList()
        // The map is UV to UV, so it is scale-free. Scaling the canvas and not the bounds is the
        // classic export bug: correct on screen, everything displaced at any multiplier.
        atFour shouldBe atOne
    }

    @Test
    fun `isolation is counted in the memory estimate`() {
        val flat = document(box("a"), box("b"))
        val grouped = document(
            Layer.Group(LayerId("g"), opacity = 0.5f, children = listOf(box("a"), box("b"))),
        )
        // A nested group holds a pair of canvas-sized buffers for as long as its children are being
        // drawn; on a large export those dominate what the effect graphs ask for, so an estimate
        // that ignores them accepts an export that then runs out of memory.
        (renderer.estimateBytes(grouped) > renderer.estimateBytes(flat)) shouldBe true
    }
}

/** The recorder, with texture creation counted so cache behaviour is assertable. */
private open class MaskRecordingDevice : ir.pixellab.core.render.GlDevice {
    override val maxTextureSize = 4096
    var created = 0
    var deleted = 0
    val uploads = ArrayList<ir.pixellab.core.render.TextureHandle>()
    val programs = ArrayList<String>()
    private var next = 1

    override fun createTexture(width: Int, height: Int, bytesPerPixel: Int): ir.pixellab.core.render.TextureHandle {
        created++
        return ir.pixellab.core.render.TextureHandle(next++)
    }

    override fun uploadArgb(
        handle: ir.pixellab.core.render.TextureHandle,
        width: Int,
        height: Int,
        pixels: IntArray,
    ) {
        // The one-by-one white stand-in is bound whenever a sampler is unused; counting it would
        // make every cache assertion in this file depend on how many layers had no mask.
        if (width > 1 || height > 1) uploads += handle
    }

    override fun uploadFloats(
        handle: ir.pixellab.core.render.TextureHandle,
        width: Int,
        height: Int,
        channels: Int,
        values: FloatArray,
    ) = Unit

    override fun deleteTexture(handle: ir.pixellab.core.render.TextureHandle) {
        deleted++
    }

    override fun bindTarget(handle: ir.pixellab.core.render.TextureHandle?) = Unit
    override fun clearTarget() = Unit
    override fun setBlend(enabled: Boolean) = Unit

    override fun useProgram(shaderId: String): Boolean {
        programs += shaderId
        passes += Pass(shaderId)
        return true
    }

    override fun bindInput(samplerName: String, handle: ir.pixellab.core.render.TextureHandle) {
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
        val inputs = LinkedHashMap<String, ir.pixellab.core.render.TextureHandle>()
        val floats = LinkedHashMap<String, Float>()
        val ints = LinkedHashMap<String, Int>()
        val matrices = LinkedHashMap<String, FloatArray>()
    }

    val passes = ArrayList<Pass>()
}
