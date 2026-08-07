package ir.pixellab.app

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether a number reads the way it was written.
 *
 * ### The defect
 *
 * The interface is right-to-left throughout, and a compound read-out is a *run of tokens* — so the
 * bidirectional algorithm lays `1080 × 1920` out in the paragraph's direction and paints it as
 * `1920 × 1080`. Every dimension in the application was reversed: the template grid advertised the
 * story size as landscape, the canvas read-out reported a portrait document as wide, and the export
 * sheet told the user they were about to write a file of the wrong shape.
 *
 * It is a good example of a class of bug that no amount of reading catches. The string is correct.
 * The composable is correct. The layout direction is correct — it is *deliberately* right-to-left,
 * because the interface is Persian. Only the rendered frame is wrong, and it took looking at a
 * screenshot of the template grid to see it.
 *
 * The fix is on [NumericStyle] rather than at each call site, because that is exactly the set of
 * values this style exists for — a size, a percentage, a coordinate; things the user could type
 * back in, which is also why it carries tabular figures. Fixing it per call site means finding all
 * of them, and then finding the next one somebody writes.
 *
 * Note this is *not* about the digits. ۱۰۸۰ is written left to right in Persian exactly as 1080 is
 * in English, and «امبر — فارسی» renders Persian digits and needs this just the same.
 *
 * ### Two things this file learned the hard way
 *
 * **It does not loop over the four directions.** It did, and calling `Metrics.use(skin)` to do so
 * changed the direction that `ScreenshotTest`, `TouchTargetTest` and `ScreenReaderTest` — which run
 * afterwards in the same worker — compose in. [Metrics] is a process-global singleton. Thirty-two
 * of their tests failed with `AppNotIdleException` inside `./gradlew build` while every one of them
 * passed when run alone, and deleting *this file* made the module green in twenty-six seconds. An
 * `@After` that put the direction back was not enough. **A unit test that writes global interface
 * state is not a unit test.** Reading the default is enough for the regression this exists for —
 * somebody deleting the `textDirection` — and all four directions share one builder, so there is no
 * fifth case hiding.
 *
 * **It runs under Robolectric despite asserting on a plain value.** Reading [NumericStyle] builds
 * the style, which loads `R.font.vazirmatn` — an Android *resource* font. `ThemeContrastTest` and
 * `ThemeSwitchTest` are plain JUnit and have always been fine because they touch only colours; a
 * resource font constructed on the bare JVM initialises Compose's font machinery outside any
 * sandbox, and the Robolectric classes that follow inherit it half-built. That was the second half
 * of the same afternoon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class NumericReadoutTest {

    @Test
    fun `the read-out style is laid out left to right`() {
        assertEquals(
            "the numeric style lays out in the paragraph direction again. In a right-to-left " +
                "interface that renders «1080 × 1920» as «1920 × 1080», and every dimension in " +
                "the application reads backwards.",
            TextDirection.Ltr,
            NumericStyle.textDirection,
        )
    }

    @Test
    fun `a read-out with a unit word is laid out in the paragraph direction`() {
        // The other half of the same bug, found the same way — in a screenshot.
        //
        // Forcing left-to-right is right for a run that is *only* numbers and symbols. It is wrong
        // the moment a Persian word joins the run, because the whole forced-LTR paragraph is then
        // laid out left to right: «۲ لایه» painted as «لایه ۲», «۸ از ۲۴» as «۸ ۲۴ از», and the
        // console menu bar's «۱۸۷ م‌ب» as «م‌ب ۱۸۷». Four read-outs shipped that way.
        //
        // `Content` rather than `Rtl`: the direction should follow the text, so a value that
        // happens to be all Latin still reads correctly.
        assertEquals(
            "MeasureStyle forces a direction again. It exists precisely because NumericStyle's " +
                "forced Ltr reverses any read-out that carries a Persian unit word.",
            TextDirection.Content,
            MeasureStyle.textDirection,
        )
    }

    @Test
    fun `the two read-out styles differ only in direction`() {
        // If they drift apart on anything else, the two halves of one read-out — «۱۰۸۰ × ۱۰۸۰» on
        // one style and «۲ لایه» on the other, side by side in the same header — stop matching.
        assertEquals(NumericStyle.fontFeatureSettings, MeasureStyle.fontFeatureSettings)
        assertEquals(NumericStyle.fontSize, MeasureStyle.fontSize)
        assertEquals(NumericStyle.fontFamily, MeasureStyle.fontFamily)
        assertEquals(NumericStyle.fontWeight, MeasureStyle.fontWeight)
    }

    @Test
    fun `the read-out style keeps its tabular figures`() {
        // The other half of why this style exists, and the half a `copy()` is most likely to drop:
        // a proportional `1` is narrower than a `0`, so a value that counts while a slider moves
        // jitters sideways under the finger and a column of them refuses to line up.
        assertEquals("tnum", NumericStyle.fontFeatureSettings)
    }
}
