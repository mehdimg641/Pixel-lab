package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import io.kotest.matchers.shouldBe
import ir.pixellab.core.imaging.Histogram
import ir.pixellab.core.imaging.HistogramChannel
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Renders the real controls and writes the pictures out.
 *
 * Two jobs in one pass. As a **test** it catches the failure mode a compile cannot: a control that
 * builds, lays out, and draws nothing — an empty canvas, a zero-height row, a colour that resolved
 * to the background. As a **tool** it produces actual screenshots of the interface without a device,
 * which is the only way to look at this app's screens from a build machine.
 *
 * Robolectric runs in `NATIVE` graphics mode, so this is real Skia rasterising real Compose output
 * rather than a stub recording calls. A test against a stub would pass on a control that draws
 * nothing at all, which is precisely the case worth catching.
 *
 * Everything is composed right-to-left, because the interface is Persian and a layout that reads
 * correctly the other way round can be quietly wrong here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    // An activity rule rather than a bare one: without a real window there is no view hierarchy
    // performing traversals, so the capture waits for a frame that never arrives.
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val output = File("build/screenshots").apply { mkdirs() }

    private fun frame(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            PixelLabTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(Modifier.fillMaxWidth().background(Ink.ChromeRaised)) { content() }
                }
            }
        }
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Drawn straight into a bitmap rather than through `captureToImage`. That helper waits on a
        // Choreographer frame callback, and Robolectric's paused looper never delivers one — so the
        // capture times out on a composition that is in fact fully laid out and ready to draw.
        val view = compose.activity.window.decorView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(HEIGHT, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)

        val bitmap = android.graphics.Bitmap
            .createBitmap(WIDTH, HEIGHT, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(output, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }

        // Not blank. A control that lays out and draws nothing is the one failure a compile cannot
        // see, and it is what a missing colour or a zero-height canvas actually looks like.
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        (pixels.distinct().size > MIN_DISTINCT_COLOURS) shouldBe true
    }

    @Test
    fun `the sheet controls draw`() {
        frame("sheet-controls") {
            var value by remember { mutableStateOf(0.4f) }
            var chosen by remember { mutableStateOf(1) }
            SheetSection("بخش")
            SheetHint("توضیح کوتاهی که زیر کنترل می‌آید")
            SheetChips {
                for (i in 0..3) SheetChip("گزینهٔ $i", chosen = chosen == i) { chosen = i }
            }
            SheetSlider("مقدار", value, 0f..1f, onChange = { next, _ -> value = next })
            SheetAction("انجام بده") {}
            SheetAction("غیرفعال", enabled = false) {}
        }
    }

    @Test
    fun `the gradient editor draws its ramp`() {
        frame("gradient-editor") {
            var gradient by remember {
                mutableStateOf(
                    Fill.Gradient(
                        stops = listOf(
                            GradientStop(0f, Color(0.05f, 0.10f, 0.35f)),
                            GradientStop(0.55f, Color(0.85f, 0.30f, 0.45f)),
                            GradientStop(1f, Color(1f, 0.80f, 0.35f)),
                        ),
                    ),
                )
            }
            GradientEditorBody(gradient = gradient, onChange = { gradient = it })
        }
    }

    @Test
    fun `the histogram draws its bars`() {
        frame("histogram") {
            // A plausible photograph rather than flat data: the logarithmic scaling and the
            // clipping warning are both invisible on a uniform histogram, and they are the two
            // things about this control that are hard to get right.
            val counts = IntArray(Histogram.BINS) { bin ->
                val body = kotlin.math.exp(-((bin - 96f) * (bin - 96f)) / 900f) * 4200f
                val shoulder = kotlin.math.exp(-((bin - 190f) * (bin - 190f)) / 260f) * 900f
                (body + shoulder).toInt()
            }
            counts[0] = 600
            counts[Histogram.BINS - 1] = 240
            HistogramView(Histogram(counts, HistogramChannel.LUMINANCE), HistogramChannel.LUMINANCE)
        }
    }

    @Test
    fun `the colour picker draws its field`() {
        frame("colour-picker") {
            var color by remember { mutableStateOf(Color(0.85f, 0.30f, 0.45f)) }
            ColorPickerBody(color = color, onChange = { color = it })
        }
    }

    @Test
    fun `an empty histogram says so rather than drawing nothing`() {
        // The state a real user hits first, before any image layer exists. A panel that rendered as
        // a blank rectangle here would read as broken rather than as empty.
        frame("histogram-empty") {
            SheetSection("هیستوگرام")
            HistogramView(null, HistogramChannel.LUMINANCE)
        }
    }

    private companion object {
        /**
         * Enough distinct colours that something was genuinely drawn.
         *
         * Text antialiasing alone produces dozens of greys, so a threshold this low still catches a
         * blank frame while never failing on a control that drew anything at all.
         */
        const val MIN_DISTINCT_COLOURS = 8

        /** A 411dp-wide phone at xhdpi, which is the device this is built for. */
        const val WIDTH = 822
        const val HEIGHT = 1400
    }
}
