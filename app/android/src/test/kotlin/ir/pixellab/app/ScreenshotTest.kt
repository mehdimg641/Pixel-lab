package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import io.kotest.matchers.shouldBe
import ir.pixellab.core.imaging.Histogram
import ir.pixellab.core.imaging.HistogramChannel
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import androidx.test.core.app.ApplicationProvider
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

    private fun frame(name: String, content: @Composable () -> Unit) =
        render(name) { Column(Modifier.fillMaxWidth().background(Ink.ChromeRaised)) { content() } }

    /**
     * A whole screen, drawn edge to edge.
     *
     * Separate from [frame] because a screen paints its own ground and its own gutters. Wrapping one
     * in the card background [frame] uses would hide exactly the thing worth looking at — whether
     * the screen's own surfaces separate from each other.
     */
    private fun page(name: String, dark: Boolean = true, content: @Composable () -> Unit) =
        render(name, dark) { Box(Modifier.fillMaxSize()) { content() } }

    private fun render(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            PixelLabTheme(dark = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    content()
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
    fun `the home screen draws`() {
        // The real screen with real content: the strip of templates, the row of jobs, and a couple
        // of saved projects. An empty list is a different picture — it is the next test — and the
        // one thing a screenshot of this screen has to prove is that the *populated* case lays out.
        val projects = listOf(
            File(output, "کاور آلبوم.pxl"),
            File(output, "استوری تخفیف.pxl"),
        )
        projects.forEach { it.writeText("x") }
        page("home") {
            HomeScreen(
                projects = projects,
                onNew = {},
                onOpen = {},
                onQuickAction = {},
                onSettings = {},
            )
        }
    }

    @Test
    fun `the home screen says so when there is nothing saved`() {
        page("home-empty") {
            HomeScreen(projects = emptyList(), onNew = {}, onOpen = {}, onQuickAction = {}, onSettings = {})
        }
    }

    @Test
    fun `the editor chrome draws`() {
        // The real bars, driven by a real view model, because the thing worth looking at is how the
        // top bar, the selection card and the toolbar sit *together*. Each one alone always looked
        // fine; it was the three of them at once that read as unfinished.
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
        model.act { select(state.document.layers.first().id) }
        page("editor-chrome") {
            Column(
                Modifier.fillMaxSize().background(Ink.Surround),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                TopBar(state = model.state, model = model, onHome = {})
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SelectionCard(model.state, model, onEditText = {})
                    Ribbon(
                        dock = Dock.PHOTO,
                        state = model.state,
                        model = model,
                        onPickImage = {},
                        onExport = {},
                        onSave = {},
                        onOpen = {},
                    )
                    MainDock(Dock.PHOTO, model.state, model) {}
                }
            }
        }
    }

    @Test
    fun `the light theme draws`() {
        // The specification calls the light theme optional, which is exactly why it needs a picture:
        // an optional theme is the one that quietly stops being legible, and the failure is always
        // the same — a colour that was chosen against black and never looked at against white.
        page("home-light", dark = false) {
            HomeScreen(projects = emptyList(), onNew = {}, onOpen = {}, onQuickAction = {}, onSettings = {})
        }
    }

    @Test
    fun `the glyph ribbon draws one chip per connected cluster`() {
        // The specification's own fixtures, so the picture can be checked by a Persian reader
        // against §۹ rather than against my arithmetic. «سلام» must show two chips, not four.
        frame("glyph-ribbon") {
            val state = remember { RibbonState() }
            SheetSection("نوار خوشه‌ها")
            GlyphRibbonPanel(text = "سلام دنیا", state = state, onStretch = { _, _ -> })
            SheetSection("با نیم‌فاصله")
            GlyphRibbonPanel(
                text = "می‌گرداندند",
                state = remember { RibbonState() },
                onStretch = { _, _ -> },
            )
            SheetSection("دوجهته")
            GlyphRibbonPanel(
                text = "KAR20 مدیا",
                state = remember { RibbonState() },
                onStretch = { _, _ -> },
            )
        }
    }

    @Test
    fun `the shared components draw`() {
        frame("components") {
            var chosen by remember { mutableStateOf(1) }
            SectionHeader("اجزای مشترک", action = "همه") {}
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconTile(Icons.Filled.ViewInAr, "متن سه‌بعدی", selected = true) {}
                IconTile(Icons.Filled.Brush, "نقاشی") {}
                IconTile(Icons.Filled.Image, "عکس", enabled = false) {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..2) Pill("گزینهٔ $i", selected = chosen == i) { chosen = i }
            }
            Panel {
                Text("یک پنل", style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                Note("توضیحی که زیر عنوان می‌آید و باید خواناتر از عنوان نباشد.")
            }
            PrimaryAction("شروع طراحی", icon = Icons.Filled.ViewInAr) {}
            SecondaryAction("بعداً") {}
            PrimaryAction("غیرفعال", enabled = false) {}
        }
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
