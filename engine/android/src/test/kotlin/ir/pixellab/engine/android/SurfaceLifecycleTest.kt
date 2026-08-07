package ir.pixellab.engine.android

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a renderer knows when it is built, and what it must be told again when it is rebuilt.
 *
 * ### The defect
 *
 * A user opened the align panel, touched a number field, the soft keyboard came up — and every
 * piece of text in the document vanished, permanently, with the selection handles still drawn
 * correctly around the empty space. It looked like the text had been deleted by the panel.
 *
 * What actually happened: the keyboard resized the window, Android destroyed and recreated the
 * drawing surface, and the render thread restarted. The restart builds a **new** `DocumentRenderer`
 * whose font resolver is `FontResolver.NONE`, and it only asked for the real one if a
 * "fonts changed" flag was set — a flag the *previous* thread had already consumed. So the new
 * renderer had no fonts and drew nothing, while the handles stayed right because they measure
 * through `LayerMeasure`, which belongs to the view model and never lost anything.
 *
 * Rotation, split screen and an insets change do the same thing. It was reachable within a minute
 * of opening the application and no test here could see it, because every test builds its renderer
 * once.
 *
 * ### What is checked
 *
 * That a renderer built fresh — which is exactly what a surface restart produces — reports having
 * no fonts, and that the surface hands them over without waiting to be asked. The second half is
 * asserted against the source, because the loop it lives in needs a real EGL surface to run and
 * there is none here; that is precisely why the bug survived, and a check that reads the code is
 * worth more than no check at all.
 */
class SurfaceLifecycleTest {

    private fun source(): String =
        java.io.File("src/main/kotlin/ir/pixellab/engine/android/CanvasSurface.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("CanvasSurface.kt not found; this test runs from the engine module's directory")

    @Test
    fun `a fresh renderer starts with no fonts, which is why it has to be told`() {
        // The premise of the whole defect, stated so it cannot quietly stop being true. If a future
        // change gave `DocumentRenderer` a sensible default the test below would still pass while
        // becoming pointless, and this is the line that would notice.
        val declared = java.io.File("src/main/kotlin/ir/pixellab/engine/android/DocumentRenderer.kt").readText()
        assertTrue(
            "DocumentRenderer no longer starts from FontResolver.NONE; check whether the surface " +
                "still needs to hand its fonts over on every restart",
            "fonts: FontResolver = FontResolver.NONE" in declared,
        )
    }

    @Test
    fun `the surface hands over fonts and assets before its first frame, not behind a flag`() {
        val body = source()
        val loop = body.substringAfter("private fun loop(")
        val beforeLoop = loop.substringBefore("while (running.get())")

        assertTrue(
            "the render loop no longer sets the renderer's fonts unconditionally after building " +
                "it. Behind a changed-flag, a surface rebuilt by the keyboard or a rotation gets a " +
                "renderer with no fonts and every text layer draws blank — permanently.",
            "renderer.setFonts(fonts)" in beforeLoop,
        )
        assertTrue(
            "the render loop no longer hands its assets over on construction; image layers would " +
                "go blank the same way text did",
            "renderer.assets = assets" in beforeLoop,
        )
    }

    @Test
    fun `the changed flags are cleared once the state has been handed over`() {
        // Otherwise the first frame after a restart applies everything twice — which is harmless
        // for fonts and throws away every cached silhouette, so the first frame after every
        // keyboard dismissal would re-shape the entire document.
        val beforeLoop = source().substringAfter("private fun loop(").substringBefore("while (running.get())")
        assertTrue("fontsChanged.set(false)" in beforeLoop)
        assertTrue("assetsChanged.set(false)" in beforeLoop)
    }
}
