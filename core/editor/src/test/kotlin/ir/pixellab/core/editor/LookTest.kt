package ir.pixellab.core.editor

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * Saved Looks.
 *
 * Two things here are easy to get wrong and invisible once wrong: the stacking order, because
 * adjustments do not commute; and the frozen measurements, because carrying one photograph's
 * histogram to another produces a result that looks plausible and means nothing.
 */
class LookTest {

    private fun photo(id: String = "photo") = Layer.Shape(
        id = LayerId(id),
        geometry = ShapeGeometry.Rectangle(Vec2(100f, 100f)),
    )

    private fun adjust(id: String, adjustment: Adjustment, name: String = id) =
        Layer.AdjustmentLayer(id = LayerId(id), adjustment = adjustment, name = name)

    private fun document(vararg layers: Layer) = Document(
        id = DocumentId("d"),
        canvas = CanvasSpec(100, 100),
        layers = layers.toList(),
    )

    @Test
    fun `a look captures the adjustments and leaves the picture behind`() {
        // The line the whole feature rests on. A Look carrying layers would replace the photograph
        // it is applied to.
        val look = Look.from(
            document(photo(), adjust("a", Adjustment.BrightnessContrast(brightness = 0.2f))),
            "گرم",
        )
        look.adjustments.size shouldBe 1
        look.adjustments.first().adjustment shouldBe Adjustment.BrightnessContrast(brightness = 0.2f)
    }

    @Test
    fun `the stacking order survives, because adjustments do not commute`() {
        val look = Look.from(
            document(
                photo(),
                adjust("first", Adjustment.Vibrance(vibrance = 0.5f)),
                adjust("second", Adjustment.BrightnessContrast(contrast = 0.3f)),
            ),
            "ترتیب",
        )
        look.adjustments.map { it.name } shouldBe listOf("first", "second")

        val applied = document(photo()).withLook(look)
        applied.layers.filterIsInstance<Layer.AdjustmentLayer>().map { it.name } shouldBe
            listOf("first", "second")
    }

    @Test
    fun `a hidden adjustment is not part of the look`() {
        // What the user turned off is what they decided against, and a Look is what they decided on.
        val look = Look.from(
            document(
                photo(),
                adjust("on", Adjustment.Vibrance(vibrance = 0.4f)),
                adjust("off", Adjustment.BrightnessContrast(brightness = 0.9f)).copy(visible = false),
            ),
            "نیمه",
        )
        look.adjustments.map { it.name } shouldBe listOf("on")
    }

    @Test
    fun `equalize travels without the histogram it was measured from`() {
        // The subtle one. Equalize's mapping *is* the picture's own cumulative histogram; carrying
        // it to a second photograph equalises that photograph by the first one's distribution, which
        // looks plausible and means nothing.
        val measured = Adjustment.Equalize(table = List(256) { it / 255f })
        val look = Look.from(document(photo(), adjust("eq", measured)), "یکنواخت")
        (look.adjustments.first().adjustment as Adjustment.Equalize).table shouldBe emptyList()
    }

    @Test
    fun `shadows and highlights travels without the clip points it measured`() {
        val measured = Adjustment.ShadowsHighlights(shadowAmount = 0.7f, blackPoint = 0.31f, whitePoint = 0.82f)
        val look = Look.from(document(photo(), adjust("sh", measured)), "سایه")
        val carried = look.adjustments.first().adjustment as Adjustment.ShadowsHighlights
        // The settings travel...
        carried.shadowAmount shouldBe 0.7f
        // ...the measurement does not.
        carried.blackPoint shouldBe 0f
        carried.whitePoint shouldBe 1f
    }

    @Test
    fun `match colour is dropped rather than carried with a dangling source`() {
        // Its whole content is a reference to an asset that the receiving document does not have.
        // A correction with no source is not a weaker version of the effect, it is an arbitrary one.
        val look = Look.from(
            document(
                photo(),
                adjust("match", Adjustment.MatchColor(source = ir.pixellab.core.model.AssetId("ref"))),
                adjust("keep", Adjustment.Vibrance(vibrance = 0.2f)),
            ),
            "تطبیق",
        )
        look.adjustments.map { it.name } shouldBe listOf("keep")
    }

    @Test
    fun `a look lands on top, where a grade belongs`() {
        // An adjustment beneath the photograph corrects the empty space under it.
        val look = Look.from(document(photo(), adjust("a", Adjustment.Vibrance())), "بالا")
        val applied = document(photo("picture")).withLook(look)
        applied.layers.first().id shouldBe LayerId("picture")
        (applied.layers.last() is Layer.AdjustmentLayer) shouldBe true
    }

    @Test
    fun `applying twice replaces rather than doubles`() {
        // Otherwise a user comparing two Looks ends up wearing both and cannot tell which produced
        // what they are looking at.
        val look = Look.from(document(photo(), adjust("a", Adjustment.Vibrance(vibrance = 0.3f))), "دوباره")
        val once = document(photo()).withLook(look)
        val twice = once.withLook(look)
        twice.layers.size shouldBe once.layers.size
    }

    @Test
    fun `two different looks can be worn at once, and each comes off alone`() {
        // Replacement is per Look, not global: stacking a warm grade under a grain preset is a real
        // thing to want, and a blanket "remove every applied look" would make it impossible.
        val warm = Look.from(document(photo(), adjust("w", Adjustment.Vibrance(vibrance = 0.3f))), "گرم", "warm")
        val dark = Look.from(document(photo(), adjust("d", Adjustment.BrightnessContrast(brightness = -0.2f))), "تیره", "dark")

        val both = document(photo()).withLook(warm).withLook(dark)
        both.wearing(warm) shouldBe true
        both.wearing(dark) shouldBe true

        val onlyWarm = both.withoutLook(dark)
        onlyWarm.wearing(warm) shouldBe true
        onlyWarm.wearing(dark) shouldBe false
    }

    @Test
    fun `an empty look is not reported as worn`() {
        // Otherwise every document wears every empty Look, and the chips all read as chosen.
        val empty = Look("خالی", "empty")
        document(photo()).wearing(empty) shouldBe false
    }

    @Test
    fun `a look captures adjustments nested in a group`() {
        // A user who tidied their grade into a folder does not expect to save half of it.
        val grouped = document(
            photo(),
            Layer.Group(
                id = LayerId("folder"),
                children = listOf(adjust("inside", Adjustment.Curves(red = Curve.LINEAR))),
            ),
        )
        Look.from(grouped, "پوشه").adjustments.map { it.name } shouldBe listOf("inside")
    }

    @Test
    fun `a persian name still yields a usable handle`() {
        // A slug of Persian text is a row of dashes, so it does not attempt one; what matters is
        // that the result is non-empty and file-safe rather than that it is readable.
        val handle = Look.slug("گرم و روشن")
        handle.isNotBlank() shouldBe true
        handle.none { it == '/' || it == '\\' || it.isWhitespace() } shouldBe true
    }

    @Test
    fun `handles from different names do not silently collide into an empty string`() {
        Look.slug("گرم") shouldBe Look.slug("روشن")
        // Which is exactly why the caller supplies an id rather than trusting the slug — recorded
        // here so the next person does not "fix" the slug and assume uniqueness they do not have.
        Look.from(document(photo()), "گرم", id = "warm").id shouldBe "warm"
    }
}
