package ir.pixellab.core.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DocumentTest {

    private fun sampleDocument(): Document {
        val heading = Layer.Text(
            id = LayerId("t1"),
            spec = TextSpec(
                text = "سلام دنیا",
                font = FontRef(
                    family = "Dana",
                    postScriptName = "DanaVF-Regular",
                    variations = mapOf(FontRef.AXIS_WEIGHT to 700f, "KASH" to 65f),
                    features = mapOf("ss01" to 1),
                ),
                size = 180f,
                paragraph = ParagraphStyle(
                    align = TextAlign.CENTER,
                    direction = TextDirection.RTL,
                    kashida = KashidaMode.VARIABLE_AXIS,
                    kashidaAmount = 0.65f,
                ),
            ),
            style = Style(
                fill = Fill.Gradient(
                    stops = listOf(
                        GradientStop(0f, Color.parse("#FFD86B")),
                        GradientStop(1f, Color.parse("#C8791A")),
                    ),
                    angle = 90f,
                ),
                effects = listOf(
                    Effect.Stroke(width = 6f, fill = Fill.Solid(Color.parse("#3A1D00"))),
                    Effect.Stroke(width = 12f, fill = Fill.Solid(Color.WHITE)),
                    Effect.Extrude(steps = 29, stepOffset = Vec2(-3f, 3f)),
                    Effect.DropShadow(distance = 6f, blur = 13f),
                    Effect.DropShadow(distance = 16f, blur = 29f),
                    Effect.DropShadow(distance = 71f, blur = 111f),
                ),
            ),
        )
        val group = Layer.Group(
            id = LayerId("g1"),
            children = listOf(heading),
            passThrough = false,
            style = Style(effects = listOf(Effect.BackdropBlur(radius = 24f, grain = 0.05f))),
        )
        return Document.blank(DocumentId("d1"), 1080, 1350).copy(layers = listOf(group))
    }

    @Test
    fun `document survives a json round trip`() {
        val original = sampleDocument()
        val restored = decodeDocument(original.encode())
        restored shouldBe original
    }

    @Test
    fun `serialised form carries a schema version`() {
        decodeDocument(sampleDocument().encode()).schemaVersion shouldBe Document.SCHEMA_VERSION
    }

    @Test
    fun `effects of the same type repeat and keep their order`() {
        val text = sampleDocument().findLayer(LayerId("t1")) as Layer.Text
        text.style.effects.filterIsInstance<Effect.Stroke>() shouldHaveSize 2
        val shadows = text.style.effects.filterIsInstance<Effect.DropShadow>()
        shadows shouldHaveSize 3
        shadows.map { it.distance } shouldBe listOf(6f, 16f, 71f)
    }

    @Test
    fun `walk yields groups before their children`() {
        sampleDocument().walk().map { it.id.value }.toList() shouldBe listOf("g1", "t1")
    }

    @Test
    fun `mapLayer rewrites a nested layer and leaves siblings alone`() {
        val updated = sampleDocument().mapLayer(LayerId("t1")) { (it as Layer.Text).copy(opacity = 0.5f) }
        updated.findLayer(LayerId("t1"))!!.opacity shouldBe 0.5f
        updated.findLayer(LayerId("g1"))!!.opacity shouldBe 1f
    }

    @Test
    fun `removeLayer prunes from inside a group`() {
        val pruned = sampleDocument().removeLayer(LayerId("t1"))
        pruned.findLayer(LayerId("t1")) shouldBe null
        (pruned.findLayer(LayerId("g1")) as Layer.Group).children shouldHaveSize 0
    }

    @Test
    fun `a group isolates when it carries effects even while passing through`() {
        val group = Layer.Group(
            id = LayerId("g"),
            passThrough = true,
            style = Style(effects = listOf(Effect.Noise())),
        )
        group.isolates shouldBe true
        group.copy(style = Style()).isolates shouldBe false
    }

    @Test
    fun `kashida axis is found under either casing`() {
        FontRef("Dana", variations = mapOf("KASH" to 40f)).kashidaAxis shouldBe 40f
        FontRef("Morabba", variations = mapOf("kash" to 80f)).kashidaAxis shouldBe 80f
        FontRef("Peyda").kashidaAxis shouldBe null
    }

    @Test
    fun `colour literals parse in all three lengths`() {
        Color.parse("#FFF") shouldBe Color(1f, 1f, 1f, 1f)
        Color.parse("#FF8000").r shouldBe 1f
        Color.parse("#FF8000").g shouldBe (0.502f plusOrMinus 0.002f)
        Color.parse("#80FF0000").a shouldBe (0.502f plusOrMinus 0.002f)
    }

    @Test
    fun `curves interpolate between control points`() {
        Curve.LINEAR.evaluate(0.25f) shouldBe (0.25f plusOrMinus 1e-4f)
        Curve.ROUNDED.evaluate(0f) shouldBe 0f
        Curve.ROUNDED.evaluate(1f) shouldBe 1f
        // A rounded shoulder rises faster than linear early on; that is what makes it read as puffy.
        (Curve.ROUNDED.evaluate(0.3f) > 0.3f) shouldBe true
    }

    @Test
    fun `degenerate structures are rejected at construction`() {
        assertThrows<IllegalArgumentException> { CanvasSpec(0, 100) }
        assertThrows<IllegalArgumentException> { Curve(listOf(Vec2(0f, 0f))) }
        assertThrows<IllegalArgumentException> {
            Fill.Gradient(stops = listOf(GradientStop(0f, Color.BLACK)))
        }
        assertThrows<IllegalArgumentException> { Effect.Extrude(steps = 0) }
    }

    @Test
    fun `unknown keys are tolerated so newer files still open`() {
        val text = sampleDocument().encode().replaceFirst("{", """{"futureField": 7,""")
        decodeDocument(text).shouldNotBeNull()
    }
}

class HistoryTest {
    private val a = Document.blank(DocumentId("d"), 100, 100, name = "a")
    private val b = a.copy(name = "b")
    private val c = a.copy(name = "c")

    @Test
    fun `undo and redo walk the stack`() {
        val history = History()
        history.record(a)
        history.record(b)
        history.canUndo shouldBe true
        history.undo(c) shouldBe b
        history.undo(b) shouldBe a
        history.canUndo shouldBe false
        history.redo(a) shouldBe b
    }

    @Test
    fun `a transaction collapses a gesture into one entry`() {
        val history = History()
        history.transaction(a) {
            repeat(120) { history.record(b) }
        }
        history.depth shouldBe 1
        history.undo(c) shouldBe a
    }

    @Test
    fun `nested transactions still produce a single entry`() {
        val history = History()
        history.transaction(a) {
            history.transaction(b) { history.record(c) }
        }
        history.depth shouldBe 1
    }

    @Test
    fun `a new edit clears the redo branch`() {
        val history = History()
        history.record(a)
        history.undo(b)
        history.canRedo shouldBe true
        history.record(c)
        history.canRedo shouldBe false
    }

    @Test
    fun `the stack is bounded`() {
        val history = History(limit = 10)
        repeat(50) { history.record(a) }
        history.depth shouldBe 10
    }
}
