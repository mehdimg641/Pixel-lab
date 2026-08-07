package ir.pixellab.core.editor

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * Lining layers up and evening out the gaps.
 *
 * The largest hole the 1500-feature audit found — about twenty rows, none of them implemented. The
 * arithmetic is simple and the ways it goes quietly wrong are not: distributing by centres instead
 * of by gaps looks right until the layers are different sizes, and aligning a single layer to the
 * selection is a no-op that reads as the button being broken.
 */
class ArrangeTest {

    private val canvas = Rect(0f, 0f, 1000f, 1000f)

    private fun placed(id: String, left: Float, top: Float, width: Float, height: Float) =
        Placed(LayerId(id), Rect(left, top, left + width, top + height))

    private fun move(moves: Map<LayerId, Vec2>, id: String) = moves[LayerId(id)] ?: Vec2.ZERO

    // ---- align ---------------------------------------------------------------------------------

    @Test
    fun `aligning left brings everything to the leftmost edge`() {
        val layers = listOf(placed("a", 100f, 0f, 50f, 50f), placed("b", 300f, 0f, 50f, 50f))
        val moves = Arrange.align(layers, AlignEdge.LEFT, AlignTarget.SELECTION, canvas)

        move(moves, "b").x shouldBe -200f
        // Already on the line, so it is not in the map at all — a caller can then tell that
        // nothing happened and skip an undo step that changes nothing.
        moves.containsKey(LayerId("a")) shouldBe false
    }

    @Test
    fun `aligning right uses each layer's own right edge`() {
        // Different widths: aligning by position instead of by edge is the classic mistake, and it
        // leaves the wider layer sticking out.
        val layers = listOf(placed("wide", 100f, 0f, 400f, 50f), placed("narrow", 200f, 0f, 50f, 50f))
        val moves = Arrange.align(layers, AlignEdge.RIGHT, AlignTarget.SELECTION, canvas)
        move(moves, "narrow").x shouldBe 250f
    }

    @Test
    fun `centring works on centres, not on corners`() {
        val layers = listOf(placed("wide", 0f, 0f, 400f, 50f), placed("narrow", 0f, 100f, 100f, 50f))
        val moves = Arrange.align(layers, AlignEdge.CENTER_X, AlignTarget.SELECTION, canvas)
        // The union spans 0..400, centre 200. The narrow layer's centre is at 50, so it moves 150.
        move(moves, "narrow").x shouldBe 150f
        move(moves, "wide").x shouldBe 0f
    }

    @Test
    fun `vertical edges only move layers vertically`() {
        val layers = listOf(placed("a", 10f, 20f, 50f, 50f), placed("b", 300f, 400f, 50f, 50f))
        for (edge in listOf(AlignEdge.TOP, AlignEdge.CENTER_Y, AlignEdge.BOTTOM)) {
            val moves = Arrange.align(layers, edge, AlignTarget.SELECTION, canvas)
            moves.values.all { it.x == 0f } shouldBe true
        }
    }

    @Test
    fun `aligning to the canvas centres a single layer on the artboard`() {
        val layers = listOf(placed("only", 0f, 0f, 200f, 100f))
        val moves = Arrange.align(layers, AlignEdge.CENTER_X, AlignTarget.CANVAS, canvas)
        // Against the selection this would be a no-op, which reads as the button being broken.
        move(moves, "only").x shouldBe 400f
    }

    @Test
    fun `aligning is idempotent`() {
        val layers = listOf(placed("a", 100f, 0f, 50f, 50f), placed("b", 300f, 0f, 80f, 50f))
        val first = Arrange.align(layers, AlignEdge.LEFT, AlignTarget.SELECTION, canvas)
        val settled = layers.map { placed ->
            val delta = first[placed.id] ?: Vec2.ZERO
            Placed(
                placed.id,
                Rect(
                    placed.bounds.left + delta.x, placed.bounds.top + delta.y,
                    placed.bounds.right + delta.x, placed.bounds.bottom + delta.y,
                ),
            )
        }
        // Pressing the button twice must not move anything the second time.
        Arrange.align(settled, AlignEdge.LEFT, AlignTarget.SELECTION, canvas).isEmpty() shouldBe true
    }

    @Test
    fun `an empty selection produces no moves`() {
        Arrange.align(emptyList(), AlignEdge.LEFT, AlignTarget.SELECTION, canvas).isEmpty() shouldBe true
    }

    // ---- distribute ----------------------------------------------------------------------------

    @Test
    fun `distributing evens the gaps, not the centres`() {
        // Widths 100, 400, 100 across a span of 0..1000. Even gaps put the wide one at 250.
        val layers = listOf(
            placed("a", 0f, 0f, 100f, 50f),
            placed("wide", 120f, 0f, 400f, 50f),
            placed("c", 900f, 0f, 100f, 50f),
        )
        val moves = Arrange.distribute(layers, DistributeAxis.HORIZONTAL)

        // 1000 span, 600 occupied, two gaps of 200 each. So: 0..100, 300..700, 900..1000.
        move(moves, "wide").x shouldBe 180f.plusOrMinus(0.01f)
        // Distributing by centres would have put the wide layer's centre at 500 — overlapping both
        // neighbours — which is the version everyone writes first.
        move(moves, "a") shouldBe Vec2.ZERO
        move(moves, "c") shouldBe Vec2.ZERO
    }

    @Test
    fun `the outermost layers never move`() {
        val layers = listOf(
            placed("a", 0f, 0f, 40f, 40f),
            placed("b", 50f, 0f, 40f, 40f),
            placed("c", 60f, 0f, 40f, 40f),
            placed("d", 500f, 0f, 40f, 40f),
        )
        val moves = Arrange.distribute(layers, DistributeAxis.HORIZONTAL)
        moves.containsKey(LayerId("a")) shouldBe false
        moves.containsKey(LayerId("d")) shouldBe false
    }

    @Test
    fun `distributing works on the order layers actually sit in, not the order given`() {
        val layers = listOf(
            placed("last", 900f, 0f, 100f, 50f),
            placed("first", 0f, 0f, 100f, 50f),
            placed("middle", 700f, 0f, 100f, 50f),
        )
        val moves = Arrange.distribute(layers, DistributeAxis.HORIZONTAL)
        // Sorted by position: first at 0, middle should land at 450, last stays at 900.
        move(moves, "middle").x shouldBe (-250f).plusOrMinus(0.01f)
        moves.containsKey(LayerId("last")) shouldBe false
    }

    @Test
    fun `two layers cannot be distributed`() {
        val layers = listOf(placed("a", 0f, 0f, 50f, 50f), placed("b", 500f, 0f, 50f, 50f))
        // One gap, no middle: there is nothing to even out, and moving either of them would be
        // inventing an intent the user did not have.
        Arrange.distribute(layers, DistributeAxis.HORIZONTAL).isEmpty() shouldBe true
    }

    @Test
    fun `distributing vertically only moves layers vertically`() {
        val layers = listOf(
            placed("a", 10f, 0f, 50f, 50f),
            placed("b", 200f, 100f, 50f, 200f),
            placed("c", 400f, 800f, 50f, 50f),
        )
        val moves = Arrange.distribute(layers, DistributeAxis.VERTICAL)
        moves.values.all { it.y != 0f && it.x == 0f } shouldBe true
    }

    @Test
    fun `distributing is idempotent`() {
        var layers = listOf(
            placed("a", 0f, 0f, 100f, 50f),
            placed("b", 120f, 0f, 300f, 50f),
            placed("c", 500f, 0f, 60f, 50f),
            placed("d", 900f, 0f, 100f, 50f),
        )
        repeat(2) {
            val moves = Arrange.distribute(layers, DistributeAxis.HORIZONTAL)
            layers = layers.map { placed ->
                val delta = moves[placed.id] ?: Vec2.ZERO
                Placed(
                    placed.id,
                    Rect(
                        placed.bounds.left + delta.x, placed.bounds.top,
                        placed.bounds.right + delta.x, placed.bounds.bottom,
                    ),
                )
            }
        }
        Arrange.distribute(layers, DistributeAxis.HORIZONTAL).isEmpty() shouldBe true
    }

    @Test
    fun `every edge and axis has its own Persian name`() {
        AlignEdge.entries.map { it.persianLabel }.distinct().size shouldBe AlignEdge.entries.size
        DistributeAxis.entries.map { it.persianLabel }.distinct().size shouldBe DistributeAxis.entries.size
        AlignTarget.entries.map { it.persianLabel }.distinct().size shouldBe AlignTarget.entries.size
    }
}

/**
 * The same operations driven through the editor, where undo and selection also have to behave.
 */
class EditorArrangeTest {

    private val boxBounds = LayerBounds { Rect(0f, 0f, 200f, 100f) }

    private fun box(id: String, at: Vec2 = Vec2.ZERO) = Layer.Shape(
        id = LayerId(id),
        geometry = ShapeGeometry.Rectangle(Vec2(200f, 100f)),
        name = id,
        transform = Transform(translation = at),
    )

    private fun editor(vararg layers: Layer) = Editor(
        Document(id = DocumentId("d"), canvas = CanvasSpec(1000, 800), layers = layers.toList()),
        boxBounds,
    )

    private fun at(e: Editor, id: String) = e.state.document.findLayer(LayerId(id))!!.transform.translation

    @Test
    fun `aligning is one undo step and comes back whole`() {
        val e = editor(box("a", Vec2(0f, 0f)), box("b", Vec2(400f, 0f)))
        val before = e.undoDepth
        e.alignLayers(listOf(LayerId("a"), LayerId("b")), AlignEdge.LEFT) shouldBe true
        e.undoDepth shouldBe before + 1

        at(e, "b").x shouldBe 0f
        e.undo()
        at(e, "b").x shouldBe 400f
    }

    @Test
    fun `aligning something already in place costs nothing`() {
        val e = editor(box("a", Vec2(50f, 0f)), box("b", Vec2(50f, 300f)))
        val before = e.undoDepth
        // Both already share a left edge; recording a step here means the user presses undo and
        // watches nothing happen.
        e.alignLayers(listOf(LayerId("a"), LayerId("b")), AlignEdge.LEFT) shouldBe false
        e.undoDepth shouldBe before
    }

    @Test
    fun `a single layer aligns to the canvas`() {
        val e = editor(box("only", Vec2(0f, 0f)))
        e.alignLayers(listOf(LayerId("only")), AlignEdge.CENTER_X) shouldBe true
        // 1000 wide canvas, 200 wide layer.
        at(e, "only").x shouldBe 400f
    }

    @Test
    fun `flipping negates one axis of the scale and leaves the other alone`() {
        val e = editor(box("a"))
        e.flipLayer(LayerId("a"), horizontal = true)
        val scale = e.state.document.findLayer(LayerId("a"))!!.transform.scale
        scale.x shouldBe -1f
        scale.y shouldBe 1f

        // Flipping back returns exactly where it started; rewriting geometry instead accumulates
        // error and eventually reverses a path's winding.
        e.flipLayer(LayerId("a"), horizontal = true)
        e.state.document.findLayer(LayerId("a"))!!.transform.scale.x shouldBe 1f
    }

    @Test
    fun `rotating the canvas swaps its sides on an odd turn`() {
        val e = editor(box("a"))
        e.rotateCanvas(1)
        e.state.document.canvas.width shouldBe 800
        e.state.document.canvas.height shouldBe 1000

        e.rotateCanvas(1)
        e.state.document.canvas.width shouldBe 1000
        e.state.document.canvas.height shouldBe 800
    }

    @Test
    fun `four quarter turns put everything back`() {
        val e = editor(box("a", Vec2(120f, 260f)))
        repeat(4) { e.rotateCanvas(1) }

        e.state.document.canvas.width shouldBe 1000
        at(e, "a").x shouldBe 120f.plusOrMinus(0.01f)
        at(e, "a").y shouldBe 260f.plusOrMinus(0.01f)
        e.state.document.findLayer(LayerId("a"))!!.transform.rotation shouldBe 360f
    }

    @Test
    fun `rotating by nothing is nothing`() {
        val e = editor(box("a"))
        val before = e.undoDepth
        e.rotateCanvas(0)
        e.rotateCanvas(4)
        e.undoDepth shouldBe before
    }

    @Test
    fun `a rotated layer stays inside the turned canvas`() {
        // A layer against the right edge of a wide canvas must land against the bottom of the
        // tall one, not off the side of it.
        val e = editor(box("a", Vec2(780f, 0f)))
        e.rotateCanvas(1)
        val moved = at(e, "a")
        (moved.x in -200f..800f) shouldBe true
        (moved.y in -200f..1000f) shouldBe true
    }

    @Test
    fun `merging replaces the run and takes the topmost place`() {
        val e = editor(box("bottom"), box("a"), box("b"), box("top"))
        val merged = box("merged")
        e.replaceWithMerged(listOf(LayerId("a"), LayerId("b")), merged) shouldBe true

        // In "b"'s place — where the combined picture actually sits — not at "a"'s, which would
        // move it behind anything that had been between them.
        e.state.document.layers.map { it.id.value } shouldBe listOf("bottom", "merged", "top")
        e.state.selection.primary shouldBe LayerId("merged")
    }

    @Test
    fun `merging fewer than two layers is refused`() {
        val e = editor(box("a"))
        e.replaceWithMerged(listOf(LayerId("a")), box("merged")) shouldBe false
    }

    @Test
    fun `merge down names the layer beneath`() {
        val e = editor(box("bottom"), box("middle"), box("top"))
        e.mergeableBelow(LayerId("top")) shouldBe listOf(LayerId("middle"), LayerId("top"))
        // Nothing beneath the bottom layer, so nothing to merge into.
        e.mergeableBelow(LayerId("bottom")).isEmpty() shouldBe true
    }

    @Test
    fun `flatten takes the visible layers only`() {
        val e = editor(box("a"), box("hidden"), box("c"))
        e.setLayerVisible(LayerId("hidden"), false)
        e.visibleLayers() shouldBe listOf(LayerId("a"), LayerId("c"))
    }
}
