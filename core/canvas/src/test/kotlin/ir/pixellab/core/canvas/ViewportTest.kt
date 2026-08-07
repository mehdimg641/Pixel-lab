package ir.pixellab.core.canvas

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

class ViewportTest {

    private val screen = Vec2(1080f, 2400f)

    private infix fun Vec2.shouldBeNear(other: Vec2) {
        x shouldBe (other.x plusOrMinus 0.01f)
        y shouldBe (other.y plusOrMinus 0.01f)
    }

    @Test
    fun `canvas and screen mappings are inverses under pan, zoom and rotation`() {
        val viewport = Viewport(offset = Vec2(120f, -80f), zoom = 2.5f, rotation = 37f, screenSize = screen)
        val point = Vec2(410f, 190f)
        viewport.toCanvas(viewport.toScreen(point)) shouldBeNear point
    }

    @Test
    fun `zooming leaves the canvas point under the finger where it was`() {
        val viewport = Viewport(offset = Vec2(50f, 60f), zoom = 1f, screenSize = screen)
        val pivot = Vec2(700f, 1500f)
        val before = viewport.toCanvas(pivot)
        // Zooming about the screen centre instead is what makes artwork slide out from under a pinch.
        viewport.zoomBy(2.4f, pivot).toCanvas(pivot) shouldBeNear before
    }

    @Test
    fun `rotating leaves the canvas point under the finger where it was`() {
        val viewport = Viewport(offset = Vec2(50f, 60f), zoom = 1.4f, screenSize = screen)
        val pivot = Vec2(300f, 900f)
        val before = viewport.toCanvas(pivot)
        viewport.rotateBy(41f, pivot).toCanvas(pivot) shouldBeNear before
    }

    @Test
    fun `a combined gesture keeps its pivot fixed too`() {
        val viewport = Viewport(zoom = 1f, screenSize = screen)
        val pivot = Vec2(540f, 1200f)
        val before = viewport.toCanvas(pivot)
        // Pan is applied on top, so the pivot moves by exactly the pan and nothing else.
        val after = viewport.transformBy(Vec2(30f, -20f), 1.7f, 12f, pivot)
        after.toScreen(before) shouldBeNear (pivot + Vec2(30f, -20f))
    }

    @Test
    fun `zoom is clamped at both ends`() {
        val viewport = Viewport(zoom = 1f, screenSize = screen)
        viewport.zoomBy(1000f, Vec2.ZERO).zoom shouldBe Viewport.MAX_ZOOM
        viewport.zoomBy(0.00001f, Vec2.ZERO).zoom shouldBe Viewport.MIN_ZOOM
    }

    @Test
    fun `a clamped zoom does not shift the view`() {
        // Rejecting the scale but keeping the pivot correction would drift the canvas every time the
        // user pinches at the limit.
        val viewport = Viewport(offset = Vec2(11f, 22f), zoom = Viewport.MAX_ZOOM, screenSize = screen)
        viewport.zoomBy(4f, Vec2(500f, 500f)) shouldBe viewport
    }

    @Test
    fun `fit centres the canvas and leaves the padding`() {
        val fitted = Viewport(screenSize = screen).fit(Vec2(1000f, 1000f), padding = 40f)
        // The narrow axis decides: (1080 - 80) / 1000.
        fitted.zoom shouldBe (1f plusOrMinus 0.001f)
        fitted.toScreen(Vec2(500f, 500f)) shouldBeNear Vec2(540f, 1200f)
    }

    @Test
    fun `fit on a wide canvas is limited by the screen width`() {
        val fitted = Viewport(screenSize = screen).fit(Vec2(4000f, 1000f))
        fitted.zoom shouldBe (0.27f plusOrMinus 0.001f)
        fitted.screenBounds(Vec2(4000f, 1000f)).width shouldBe (1080f plusOrMinus 0.5f)
    }

    @Test
    fun `fit centres the canvas in what the bars leave visible, not in the whole screen`() {
        // The bug this exists for: the canvas fills the screen and the fixed bars are drawn over it,
        // so fitting to the whole screen puts the document's middle behind them. On a phone that is
        // around 200dp of 900, and the result is artwork sitting low with its bottom edge under the
        // ribbon — which reads as the app being unable to show a document properly.
        val top = 150f
        val bottom = 400f
        val fitted = Viewport(screenSize = screen).fit(Vec2(1000f, 1000f), insetTop = top, insetBottom = bottom)
        val centre = fitted.toScreen(Vec2(500f, 500f))
        centre.x shouldBe (screen.x / 2f plusOrMinus 0.01f)
        centre.y shouldBe (top + (screen.y - top - bottom) / 2f plusOrMinus 0.01f)
    }

    @Test
    fun `a tall canvas is sized to the visible height rather than the screen height`() {
        // Sizing to the full screen and then centring in the visible strip would be worse than the
        // bug it replaced: the document would be correctly centred and still run under both bars.
        val bounds = Viewport(screenSize = screen)
            .fit(Vec2(1080f, 1920f), insetTop = 150f, insetBottom = 400f)
            .screenBounds(Vec2(1080f, 1920f))
        (bounds.top >= 150f) shouldBe true
        (bounds.bottom <= screen.y - 400f) shouldBe true
    }

    @Test
    fun `insets that would swallow the screen are ignored rather than obeyed`() {
        // A one-pixel-tall working area is not a better answer than the whole screen, and a caller
        // that reports nonsense should not be able to make the canvas vanish.
        val absurd = Viewport(screenSize = screen).fit(Vec2(1000f, 1000f), insetTop = 2000f, insetBottom = 2000f)
        absurd shouldBe Viewport(screenSize = screen).fit(Vec2(1000f, 1000f))
    }

    @Test
    fun `fit does nothing before the screen is measured`() {
        val unmeasured = Viewport()
        unmeasured.fit(Vec2(1000f, 1000f)) shouldBe unmeasured
    }

    @Test
    fun `a sheet pans the canvas so the edited layer stays visible`() {
        val viewport = Viewport(screenSize = screen).fit(Vec2(1080f, 1080f))
        val layer = Rect(100f, 700f, 400f, 900f)
        val sheetHeight = 1100f
        val revealed = viewport.revealing(layer, obstructedBottom = sheetHeight)

        val centre = revealed.toScreen(Vec2(250f, 800f))
        // The visible strip is everything above the sheet; the layer's centre lands in the middle
        // of it. A sheet that covers what it edits is the single most common flaw in mobile editors.
        centre.y shouldBe ((screen.y - sheetHeight) / 2f plusOrMinus 1f)
    }

    @Test
    fun `revealing does not zoom when panning is enough`() {
        val viewport = Viewport(screenSize = screen).fit(Vec2(1080f, 1080f))
        val small = Rect(0f, 0f, 200f, 200f)
        // Changing zoom mid-edit re-renders every layer and throws away the user's framing.
        viewport.revealing(small, obstructedBottom = 900f).zoom shouldBe viewport.zoom
    }

    @Test
    fun `revealing shrinks only when the layer cannot fit in what is left`() {
        val viewport = Viewport(screenSize = screen).fit(Vec2(1080f, 1080f))
        val tall = Rect(0f, 0f, 1080f, 1080f)
        val revealed = viewport.revealing(tall, obstructedBottom = 1800f)
        (revealed.zoom < viewport.zoom) shouldBe true
        val bounds = revealed.screenBounds(Vec2(1080f, 1080f))
        (bounds.bottom <= screen.y - 1800f + 0.5f) shouldBe true
    }

    @Test
    fun `angles fold into a half turn either way`() {
        normaliseDegrees(370f) shouldBe (10f plusOrMinus 0.001f)
        normaliseDegrees(-370f) shouldBe (-10f plusOrMinus 0.001f)
        // The short way round: 350 to 10 is +20, not -340. A rotation gesture that crosses the
        // wrap point would otherwise spin the layer almost a full turn.
        deltaDegrees(from = 170f, to = -170f) shouldBe (20f plusOrMinus 0.001f)
        deltaDegrees(from = -170f, to = 170f) shouldBe (-20f plusOrMinus 0.001f)
    }

    @Test
    fun `zoom is reported the way the user reads it`() {
        Viewport(zoom = 3.2f).zoomPercent shouldBe (320f plusOrMinus 0.001f)
    }
}
