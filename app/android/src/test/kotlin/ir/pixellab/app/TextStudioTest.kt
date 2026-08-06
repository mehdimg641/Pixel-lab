package ir.pixellab.app

import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.TextSection
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.TextSpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The text panel, driven from the outside.
 *
 * The sentence is the one the whole feature was asked for: «برای اطلاع از قیمت کابینت», with
 * «کابینت» to be styled on its own. What is checked here is the *bookkeeping* — that an edit lands
 * on the word it was aimed at, that a second edit does not undo the first, and that the ranges
 * survive the two things that move them. Whether the letters still join is proved in
 * `engine:android`, where there is a real font to shape with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextStudioTest {

    private val sentence = "برای اطلاع از قیمت کابینت"
    private val word = sentence.indexOf("کابینت").let { it until sentence.length }
    private val red = Fill.Solid(Color(1f, 0.1f, 0.1f))
    private val blue = Fill.Solid(Color(0.1f, 0.2f, 1f))

    private fun model(): Pair<EditorViewModel, LayerId> {
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
        model.autoSave.stop()
        // Built directly: `addTextLayer` needs a scanned font catalogue and this environment has
        // none, so going through it would leave every assertion below vacuously true.
        val id = LayerId("headline")
        model.act {
            addLayer(
                Layer.Text(id = id, spec = TextSpec(text = sentence, font = FontRef("Vazirmatn")), name = "تیتر"),
            )
            select(id)
        }
        return model to id
    }

    private fun EditorViewModel.spec(id: LayerId) = (state.document.findLayer(id) as Layer.Text).spec

    // ---- aiming ---------------------------------------------------------------------------------

    @Test
    fun `choosing a word aims the panel at it`() {
        val (model, id) = model()
        model.act { selectTextRange(word) }
        model.state.activeTextRange shouldBe word
        model.spec(id).runs.isEmpty() shouldBe true
    }

    @Test
    fun `a range from another layer stops applying rather than aiming at the wrong letters`() {
        // Character indices mean nothing against a different string. Rather than relying on every
        // place that changes the selection to remember to clear the range, it simply stops being
        // active — so a stale one can never quietly steer the next colour change.
        val (model, id) = model()
        model.act { selectTextRange(word) }
        val other = LayerId("other")
        model.act {
            addLayer(Layer.Text(id = other, spec = TextSpec(text = "کوتاه", font = FontRef("Vazirmatn")), name = "دیگر"))
            select(other)
        }
        model.state.activeTextRange shouldBe null
        model.spec(id).runs.isEmpty() shouldBe true
    }

    @Test
    fun `a raw range is widened to whole words rather than cutting one in half`() {
        val (model, _) = model()
        model.act { selectTextRange(word.first + 1 until word.first + 3) }
        model.state.activeTextRange?.first shouldBe word.first
    }

    // ---- editing --------------------------------------------------------------------------------

    @Test
    fun `a colour aimed at a word lands on that word and nothing else`() {
        val (model, id) = model()
        model.act { selectTextRange(word) }
        model.setCharacterStyle(id, model.state.activeTextRange) { it.copy(fill = red) }

        val runs = model.spec(id).runs
        runs.size shouldBe 1
        runs[0].start shouldBe word.first
        runs[0].end shouldBe sentence.length
        runs[0].style.fill shouldBe red
    }

    @Test
    fun `setting a second property does not undo the first`() {
        // **The defect the whole edit-not-replace signature exists to prevent.** With a replacing
        // API the size would clear the colour, and per-word styling would feel like it does not
        // work — every second adjustment silently undoing the last.
        val (model, id) = model()
        model.act { selectTextRange(word) }
        model.setCharacterStyle(id, model.state.activeTextRange) { it.copy(fill = red) }
        model.setCharacterStyle(id, model.state.activeTextRange) { it.copy(sizeScale = 1.5f) }

        val style = model.spec(id).runs.single().style
        style.fill shouldBe red
        style.sizeScale shouldBe 1.5f
    }

    @Test
    fun `with nothing chosen an edit applies to the whole string`() {
        val (model, id) = model()
        model.setCharacterStyle(id, null) { it.copy(fill = blue) }
        val run = model.spec(id).runs.single()
        run.start shouldBe 0
        run.end shouldBe sentence.length
    }

    @Test
    fun `clearing a range returns it to the layer's own paint`() {
        val (model, id) = model()
        model.act { selectTextRange(word) }
        model.setCharacterStyle(id, model.state.activeTextRange) { it.copy(fill = red) }
        model.clearCharacterStyle(id, word)
        model.spec(id).runs.isEmpty() shouldBe true
    }

    // ---- surviving an edit to the words ------------------------------------------------------------

    @Test
    fun `correcting a typo in front of a styled word does not slide the colour along`() {
        // Style «کابینت», then fix the beginning of the sentence. Without `retarget` every stored
        // index is out by the difference and the colour lands on the wrong letters — invisible in a
        // screenshot, immediate in use, and the single most likely way this feature would be lost.
        val (model, id) = model()
        model.setCharacterStyle(id, word) { it.copy(fill = red) }
        model.setText(id, "برایِ اطلاع از قیمت کابینت")

        val text = model.spec(id).text
        val run = model.spec(id).runs.single()
        text.substring(run.start, run.end) shouldBe "کابینت"
    }

    @Test
    fun `deleting the styled word removes its run instead of leaving a stray one`() {
        val (model, id) = model()
        model.setCharacterStyle(id, word) { it.copy(fill = red) }
        model.setText(id, "برای اطلاع از قیمت ")
        model.spec(id).runs.isEmpty() shouldBe true
    }

    // ---- the panel as a transaction ------------------------------------------------------------------

    @Test
    fun `moving between sections is the same panel, so cancel still reaches back past it`() {
        // The ✕ works from a baseline taken when the sheet's content changes, and every section is a
        // different content value. Keyed on the content itself, a user would set a shadow, glance at
        // the colour section, and find that cancelling silently no longer removed the shadow.
        val (_, id) = model()
        val shadow = SheetContent.TextStudio(id, TextSection.SHADOW)
        val colour = SheetContent.TextStudio(id, TextSection.COLOR)
        shadow.identity shouldBe colour.identity

        // And a different layer *is* a different panel, which is the other half of the rule.
        SheetContent.TextStudio(LayerId("elsewhere"), TextSection.SHADOW).identity shouldBe LayerId("elsewhere")
    }

    @Test
    fun `the panel keeps the canvas out from under itself`() {
        // Every layer-scoped sheet pans the canvas so the thing being edited is not behind the
        // panel. It works off `subject`, and a new sheet type that forgot to declare one would edit
        // a headline the user cannot see.
        val (_, id) = model()
        SheetContent.TextStudio(id, TextSection.COLOR).subject shouldBe id
    }
}
