package ir.pixellab.core.editor

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.decodeDocument
import ir.pixellab.core.model.withStyle
import ir.pixellab.core.model.encode
import org.junit.jupiter.api.Test

/**
 * The built-in styles and templates.
 *
 * A library is only worth having if every entry actually renders, so what is checked here is that
 * each one is a valid, non-empty, serialisable value — the failures a preset list attracts are
 * exactly the quiet ones, where an entry looks fine in the picker and produces nothing on the canvas.
 */
class LibraryTest {

    @Test
    fun `every style has a name and does something`() {
        Library.styles.isNotEmpty() shouldBe true
        for (preset in Library.styles) {
            withClue(preset.name) {
                preset.name.isNotBlank() shouldBe true
                // A style with no effects and a default fill is indistinguishable from no style at
                // all, which reads in the picker as the entry being broken.
                val doesSomething = preset.style.effects.isNotEmpty() ||
                    preset.style.fill != Fill.Solid(ir.pixellab.core.model.Color.BLACK) ||
                    preset.style.fillOpacity < 1f
                doesSomething shouldBe true
            }
        }
    }

    @Test
    fun `every style has a distinct id, and the cover project can find the two it needs`() {
        // The ids are what code refers to; the names are what people read. Both have to be unique,
        // and the two the cover project names by hand have to exist — a typo there is a crash on a
        // library tap, not a compile error.
        val ids = Library.styles.map { it.id }
        ids.none { it.isBlank() } shouldBe true
        ids.distinct().size shouldBe ids.size
        ids.contains("cover-frame") shouldBe true
        ids.contains("cover-face") shouldBe true
    }

    @Test
    fun `the cover project builds its three layers and links the two that must move together`() {
        val project = Library.coverProject()
        project.layers.size shouldBe 3
        // Ground first, because it is the ground: a background composited over the title would
        // hide it completely.
        project.layers.first().id.value shouldBe "cover-ground"
        // The frame's stroke sits outside the letters and the face's gradient inside them, and one
        // stroke cannot be on two sides at once — which is why this is a project and not a preset.
        val text = project.layers.filterIsInstance<ir.pixellab.core.model.Layer.Text>()
        text.size shouldBe 2
        text.all { it.transform.skew.x != 0f } shouldBe true
        project.links.groups.any {
            it.containsAll(text.map { layer -> layer.id })
        } shouldBe true
    }

    @Test
    fun `style names are distinct`() {
        Library.styles.map { it.name }.distinct().size shouldBe Library.styles.size
    }

    @Test
    fun `every style survives being saved`() {
        for (preset in Library.styles) {
            val document = Library.documentFor(Library.templates.first()).let { base ->
                base.copy(layers = base.layers.map { it.withStyle(preset.style) })
            }
            // A preset that cannot round-trip is one that vanishes the first time the user saves
            // the project they applied it to.
            withClue(preset.name) {
                decodeDocument(document.encode()).layers.first().style.effects.size shouldBe
                    preset.style.effects.size
            }
        }
    }

    @Test
    fun `the hollow style drops its fill and keeps its effects`() {
        val hollow = Library.styles.first { it.id == "hollow" }
        // Fill opacity, not layer opacity: dropping the fill to nothing has to leave every effect
        // at full strength, and that distinction is the whole trick.
        hollow.style.fillOpacity shouldBe 0f
        hollow.style.effects.isNotEmpty() shouldBe true
    }

    @Test
    fun `the gold style has a bright band in the middle of its ramp`() {
        // By id, not by name. Selecting on a *display* name is what broke here: the cover
        // frame is called «جلد سه‌بعدی — قاب طلایی» and matched a search for «طلایی» first,
        // so the test read a solid fill as a gradient. The id exists precisely so that
        // renaming something visible cannot reach into code that referred to it.
        val gold = Library.styles.first { it.id == "gold" }
        val gradient = gold.style.fill as Fill.Gradient
        val middle = gradient.stops.minByOrNull { kotlin.math.abs(it.position - 0.5f) }!!
        val ends = listOf(gradient.stops.first(), gradient.stops.last())
        // What makes gold read as metal rather than as sand: a plain dark-to-light ramp is the
        // version everyone writes first and it looks like a beach.
        for (end in ends) {
            (middle.color.r + middle.color.g + middle.color.b > end.color.r + end.color.g + end.color.b) shouldBe true
        }
    }

    @Test
    fun `every template has a positive size and a group`() {
        Library.templates.isNotEmpty() shouldBe true
        for (template in Library.templates) {
            withClue(template.name) {
                (template.width > 0 && template.height > 0) shouldBe true
                template.group.isNotBlank() shouldBe true
            }
        }
    }

    @Test
    fun `a template makes a document at its own size`() {
        for (template in Library.templates) {
            val document = Library.documentFor(template)
            withClue(template.name) {
                document.canvas.width shouldBe template.width
                document.canvas.height shouldBe template.height
            }
        }
    }

    @Test
    fun `a new document is not empty`() {
        val document = Library.documentFor(Library.templates.first())
        // An empty canvas gives the user nothing to select, nothing to style, and no idea what the
        // tools do.
        document.layers.size shouldBe 1
        (document.layers.first() is Layer.Text) shouldBe true
    }

    @Test
    fun `the starting headline scales with the canvas`() {
        val small = Library.documentFor(Library.templates.first { it.height == 638 })
        val large = Library.documentFor(Library.templates.first { it.height == 3508 })
        val sizeOf = { document: ir.pixellab.core.model.Document ->
            (document.layers.first() as Layer.Text).spec.size
        }
        // A fixed point size is illegible on a poster and larger than the card on a business card.
        (sizeOf(large) > sizeOf(small) * 4) shouldBe true
    }

    @Test
    fun `a new document survives a save`() {
        val document = Library.documentFor(Library.templates.last())
        decodeDocument(document.encode()).canvas.width shouldBe document.canvas.width
    }

    private inline fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$clue: ${e.message}", e)
        }
    }
}
