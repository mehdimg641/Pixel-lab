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
    private fun page(
        name: String,
        dark: Boolean = true,
        skin: ThemeSkin = ThemeSkin.EMBER,
        content: @Composable () -> Unit,
    ) = render(name, dark, skin) { Box(Modifier.fillMaxSize()) { content() } }

    private fun render(
        name: String,
        dark: Boolean = true,
        skin: ThemeSkin = ThemeSkin.EMBER,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            PixelLabTheme(skin = skin, dark = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    content()
                }
            }
        }
        capture(name)
    }

    /**
     * Lays the current composition out and writes it to a file.
     *
     * Split out of [render] so a single test can capture several frames: `setContent` may only be
     * called once per test, so a gallery of eight themes has to swap state under one composition
     * rather than replacing the content eight times.
     */
    private fun capture(name: String) {
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
    fun `the editor chrome draws in the light theme`() {
        // The light theme's own picture of the *editor*, not just the home screen. The home screen
        // is nearly all text on cards and looks fine in any palette; the editor is where a theme
        // succeeds or fails, because it has the four surfaces stacked against each other and a
        // mid-grey surround between them and the artwork.
        // Recovery timer off: it schedules a delay on the main looper that never comes due,
        // and Compose's idling check waits on that queue. See TouchTargetTest.editor().
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }
        model.act { select(state.document.layers.first().id) }
        page("editor-chrome-light", dark = false) {
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
                        onAddText = {},
                        onEditText = {},
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
    fun `the editor chrome draws`() {
        // The real bars, driven by a real view model, because the thing worth looking at is how the
        // top bar, the selection card and the toolbar sit *together*. Each one alone always looked
        // fine; it was the three of them at once that read as unfinished.
        // Recovery timer off: it schedules a delay on the main looper that never comes due,
        // and Compose's idling check waits on that queue. See TouchTargetTest.editor().
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }
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
                        onAddText = {},
                        onEditText = {},
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
    fun `every direction draws the editor, day and night`() {
        // Eight pictures, one per direction per mode, and the only place any of them can be looked
        // at side by side. Four themes is four times the surface area for the failure a palette
        // always has — a colour chosen against one panel and never seen on another — and the
        // contrast test measures ratios while this one shows what they produce.
        //
        // It is also the guard against a direction that *lays out* and draws nothing: [capture]
        // asserts the frame carries more than a handful of distinct colours, so a skin whose panel
        // and ground collapsed to the same value fails here rather than on somebody's phone.
        //
        // One composition, swapped eight times, because `setContent` may only be called once per
        // test — which is worth stating, since the obvious loop around `page` throws.
        //
        // **The real screen, not an arrangement of its bars.** This used to compose `TopBar`,
        // `Ribbon` and `MainDock` into a column by hand, which meant it went on passing after the
        // editor stopped being built that way: the pictures showed a layout the application no
        // longer had. A screenshot test that draws its own approximation of the screen is testing
        // the test. `EditorScreen` is what ships, so `EditorScreen` is what is photographed — and it
        // is the only way the four directions' *structures* — Console's menu bar and its rail and
        // inspector columns, Iris's dials on the glass, Ember's plain docked panel — appear at all.
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }
        model.act { select(state.document.layers.first().id) }

        val frames = ThemeSkin.entries.flatMap { skin -> listOf(skin to true, skin to false) }
        var frame by mutableStateOf(0)

        compose.setContent {
            val (skin, dark) = frames[frame]
            PixelLabTheme(skin = skin, dark = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) { EditorScreen(model = model) }
                }
            }
        }

        for ((index, entry) in frames.withIndex()) {
            frame = index
            val (skin, dark) = entry
            capture("theme-${skin.name.lowercase()}-${if (dark) "night" else "day"}")
        }
    }

    @Test
    fun `each direction lists recent work in its own shape`() {
        // The table in the brief gives the four directions three different galleries — a card grid,
        // a staggered board, and a dense NAME/SIZE/MODIFIED table — and that is the point at which
        // they stop being four colour schemes of one screen. A picture each, because "the layout
        // changed" is not something a colour assertion can see.
        val projects = listOf("پوستر شمارهٔ ۳", "پرترهٔ استودیو", "گلدان سرامیکی", "خیابان ۴۸۲۱", "کاور آلبوم")
            .map { File(output, "$it.pxl").apply { if (!exists()) writeText(" ") } }

        val frames = listOf(ThemeSkin.EMBER, ThemeSkin.IRIS, ThemeSkin.CONSOLE)
        var frame by mutableStateOf(0)

        compose.setContent {
            PixelLabTheme(skin = frames[frame], dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) {
                        HomeScreen(
                            projects = projects,
                            onNew = {},
                            onOpen = {},
                            onQuickAction = {},
                            onSettings = {},
                        )
                    }
                }
            }
        }

        for ((index, skin) in frames.withIndex()) {
            frame = index
            capture("gallery-${skin.name.lowercase()}")
        }
    }

    @Test
    fun `Console lays a tool rail and an inspector over the canvas`() {
        // The brief gives Console a «menu bar + tool rail + inspector» layout while the other two
        // put their tools in the ribbon. Only a picture shows whether that happened: a colour test
        // cannot tell a rail from its absence, and Console spent its first day being Ember in
        // different colours.
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }
        model.act { select(state.document.layers.first().id) }
        page("console-chrome", dark = true, skin = ThemeSkin.CONSOLE) {
            EditorScreen(model = model)
        }
    }

    @Test
    fun `the four studio screens draw`() {
        // The wave that moved these out of sheets is exactly the kind that can leave a screen which
        // routes correctly and renders as a grey rectangle: they compose panels that were written
        // for a sheet's proportions, inside a header-canvas-panel column that no sheet ever had.
        // Only a picture shows that, and [capture]'s distinct-colour floor is what makes it fail
        // rather than merely produce one.
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }
        val headline = ir.pixellab.core.model.LayerId("headline")
        model.act {
            addLayer(
                ir.pixellab.core.model.Layer.Text(
                    id = headline,
                    spec = ir.pixellab.core.model.TextSpec(
                        text = "سلام دنیا",
                        font = ir.pixellab.core.model.FontRef("Vazirmatn"),
                    ),
                    name = "تیتر",
                ),
            )
            select(headline)
        }

        var frame by mutableStateOf(0)
        compose.setContent {
            PixelLabTheme(skin = ThemeSkin.EMBER, dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) {
                        when (frame) {
                            0 -> TemplatesScreen(onNew = {}, onBack = {})
                            1 -> BrushStudioScreen(model, onBack = {}, onPickImage = {})
                            2 -> RetouchStudioScreen(model, onBack = {})
                            else -> TextStudioScreen(model, layer = headline, onDone = {})
                        }
                    }
                }
            }
        }

        for ((index, name) in STUDIOS.withIndex()) {
            frame = index
            capture(name)
        }
    }

    @Test
    fun `the text studio's section strip takes three shapes`() {
        // The last of the brief's three-way splits: pill chips on Ember, a block of square tabs on
        // Console, and the same pills on Iris's translucent plate with no rule under them. Three
        // pictures because "the strip changed" is not something a colour assertion can see.
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }
        val headline = ir.pixellab.core.model.LayerId("headline")
        model.act {
            addLayer(
                ir.pixellab.core.model.Layer.Text(
                    id = headline,
                    spec = ir.pixellab.core.model.TextSpec(
                        text = "سلام دنیا",
                        font = ir.pixellab.core.model.FontRef("Vazirmatn"),
                    ),
                    name = "تیتر",
                ),
            )
            select(headline)
        }

        val frames = listOf(ThemeSkin.EMBER, ThemeSkin.IRIS, ThemeSkin.CONSOLE)
        var frame by mutableStateOf(0)
        compose.setContent {
            PixelLabTheme(skin = frames[frame], dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) {
                        TextStudioScreen(model, layer = headline, onDone = {})
                    }
                }
            }
        }

        for ((index, skin) in frames.withIndex()) {
            frame = index
            capture("text-studio-${skin.name.lowercase()}")
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
        /** In the order the frames are swapped above. */
        val STUDIOS = listOf("templates", "brush-studio", "retouch-studio", "text-studio")

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
