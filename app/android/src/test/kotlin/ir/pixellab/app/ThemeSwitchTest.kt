package ir.pixellab.app

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether choosing a theme actually changes the screen.
 *
 * A light palette had existed for weeks, a control to pick it sat at the top of the settings sheet,
 * and `ScreenshotTest` rendered the whole editor in it correctly. The user still reported the
 * application was dark, and they were right: **`Ink.tokens` was a plain `var`**, so the hundreds of
 * `Ink.Text` and `Ink.Chrome` reads that paint every screen were invisible to the snapshot system.
 * Swapping the palette repainted only whatever happened to recompose for some other reason — which
 * is why the setting looked half-broken rather than simply broken.
 *
 * ### Why this tests the mechanism and not a screenshot
 *
 * Two earlier attempts watched a composable and passed *before* the fix as well as after, which is
 * worse than having no test. Recomposition is the wrong thing to watch: flipping the theme changes
 * `MaterialTheme`\'s colour scheme and a composition local with it, so most of the tree recomposes
 * anyway and picks up the new palette on the way past, observable or not. Watching the draw phase
 * fails differently — Robolectric presents no frames, so nothing draws unless the test draws it by
 * hand, and a hand-drawn frame repaints everything unconditionally.
 *
 * What actually went wrong is one property: reading a colour did not subscribe the reader to it. So
 * that is what is asserted, with the same observer Compose itself uses to decide what to invalidate.
 * A test of a mechanism is worth having when the mechanism *is* the defect.
 */
class ThemeSwitchTest {

    @Test
    fun `reading a colour is an observable read`() {
        val observed = mutableListOf<Any>()
        Ink.use(Palette.Dark)
        // The lowest level there is: Compose's own read observer, the thing that decides what gets
        // invalidated when something changes. If a colour read reports nothing here, then nothing
        // anywhere in the application is subscribed to the palette, and swapping it can only reach
        // whatever was going to repaint regardless.
        Snapshot.observe(readObserver = { observed += it }) {
            Ink.Text
            Ink.Chrome
        }
        assertTrue(
            "reading a colour reported no observable state. A screen already on the page is not " +
                "subscribed to the palette, so it keeps painting the colours it started with — " +
                "which is what \"I chose light and it is still dark\" actually is.",
            observed.isNotEmpty(),
        )
    }

    @Test
    fun `the two palettes do not paint the same`() {
        // The guard on the test above: an observable palette that happened to hold identical
        // colours would satisfy it and change nothing anybody can see.
        Ink.use(Palette.Dark)
        val dark = Ink.Text to Ink.Chrome
        Ink.use(Palette.Light)
        val light = Ink.Text to Ink.Chrome
        assertNotEquals(dark, light)
    }

    @Test
    fun `the light palette is the one a fresh launch gets`() {
        // The second half of the same complaint. Following the phone sounds like the polite default
        // and silently overruled a user who had asked for light twice, because their phone was dark.
        assertTrue(
            "the default theme is ${Preferences().theme}, not ${ThemeChoice.LIGHT}",
            Preferences().theme == ThemeChoice.LIGHT,
        )
    }
}
