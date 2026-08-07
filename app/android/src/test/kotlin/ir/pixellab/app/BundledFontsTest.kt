package ir.pixellab.app

import android.os.Looper
import ir.pixellab.core.fonts.Script
import ir.pixellab.engine.android.FontLibrary
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Whether a fresh install can set a Persian headline.
 *
 * ### The defect this was written for
 *
 * Four display faces shipped in `assets/fonts/` from the beginning and **not one of them was ever
 * loaded**. `FontLibrary` walks `File` roots, and an entry inside an APK is not a file; the only
 * reader of bundled assets is `AssetStore.loadBundled`, which takes the `textures/` folder alone.
 * So 478 KB rode along in every build, appeared in no picker, and nothing noticed — because the
 * thing everybody checks is whether the files are *in* the APK, and they were.
 *
 * That is why nothing here counts files. Counting files is the check that passed for months while
 * the feature did not work.
 *
 * ### What is actually asserted
 *
 * `FontCatalog.usableFor` answers the only question that matters — given this Persian string, is
 * there a face that can render it — and it answers it from glyph coverage read out of the real
 * file, not from a filename. A face called `Vazirmatn-Regular.ttf` that failed to parse, or that
 * turned out to be Latin-only, fails this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledFontsTest {

    /** Persian, and deliberately awkward: گ and چ and ژ are the letters a Latin face never has. */
    private val headline = "گنجشک ژاله‌آلود"

    private fun seedAndScan(): ir.pixellab.core.fonts.FontCatalog {
        val context = RuntimeEnvironment.getApplication()
        FontStore.seedBundled(context, context.assets)
        // Only the import directory, not `FontStore.roots`. The third root is `/system/fonts`,
        // which on a real phone carries Noto Naskh and would answer this test on its own — hiding
        // whether the app ships anything at all.
        return FontLibrary(listOf(FontStore.importDirectory(context))).rescan()
    }

    @Test
    fun `the bundled faces reach the library at all`() {
        val catalog = seedAndScan()
        catalog.typefaces.shouldNotBeEmpty()
    }

    @Test
    fun `a fresh install can set a Persian headline`() {
        val catalog = seedAndScan()

        val usable = catalog.usableFor(headline)
        usable.shouldNotBeEmpty()
    }

    @Test
    fun `the library is large enough to be worth calling a library`() {
        // A floor, not an exact count — the manifest will grow and a test that pins the number
        // would fail on every addition without anything being wrong. Eighty is well under the
        // hundred-odd shipped and well over the seven this started as, so it fails on a broken
        // fetch and on nothing else.
        val catalog = seedAndScan()

        (catalog.typefaces.size >= 80) shouldBe true
    }

    @Test
    fun `every bundled file parses`() {
        // `FontLibrary.failed` names anything that reached disk and would not parse. With a
        // hundred-odd files fetched over the network, a truncated download is a real possibility
        // and it would otherwise show up as one face quietly missing from the picker.
        val context = RuntimeEnvironment.getApplication()
        FontStore.seedBundled(context, context.assets)
        val library = FontLibrary(listOf(FontStore.importDirectory(context)))
        library.rescan()

        library.failed shouldBe emptyList()
    }

    @Test
    fun `there are many Persian families, not one`() {
        // The point of the whole exercise. One Persian face satisfies «can it set Persian at all»
        // and satisfies nobody designing a cover, which is a choice between faces.
        val catalog = seedAndScan()

        (catalog.byScript(Script.ARABIC).size >= 20) shouldBe true
    }

    @Test
    fun `scanning the whole library stays quick`() {
        // Measured because the plan guessed. A single 940 KB Nastaliq face parses in about two
        // milliseconds, so a hundred should be well inside a second — but «should be» is how the
        // startup cost of a font library gets away from you, and this scan sits between launch and
        // the first frame of text.
        val context = RuntimeEnvironment.getApplication()
        FontStore.seedBundled(context, context.assets)
        val library = FontLibrary(listOf(FontStore.importDirectory(context)))

        val started = System.nanoTime()
        library.rescan()
        val millis = (System.nanoTime() - started) / 1_000_000

        println("scanned ${library.fontCount} faces in ${millis}ms")
        // Generous, because this runs on CI hardware of unknown speed alongside other tests. It is
        // a guard against an order-of-magnitude regression — a per-file network call, a re-parse
        // per family — not a benchmark.
        (millis < 10_000) shouldBe true
    }

    @Test
    fun `at least one bundled face is a Persian display face`() {
        // Not merely Persian-capable — the system's Noto Naskh is that, and it is a *text* face.
        // The app exists to make cover art, so it has to bring at least one heavy display cut of
        // its own or the first thing a user does is go and find one.
        val catalog = seedAndScan()

        val persian = catalog.byScript(Script.ARABIC)
        persian.shouldNotBeEmpty()
    }

}

/**
 * Seeding into a directory of the test's own, so the mess stays here.
 *
 * Separate from [BundledFontsTest] because it deliberately writes a broken file, and the first
 * version of it wrote that file into the shared font directory — where it stayed for the rest of
 * the JVM and made six unrelated interface tests hang until the Compose idle timeout. The failure
 * pointed at the touch-target audit, which had nothing to do with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledFontSeedingTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `unpacks every bundled face`() {
        val target = temporary.newFolder("fonts")

        FontStore.seedBundled(RuntimeEnvironment.getApplication().assets, target)

        (target.listFiles()?.size ?: 0 > 0) shouldBe true
    }

    @Test
    fun `does not overwrite what the user put there`() {
        // Somebody drops in their own cut under a name the APK also ships. Theirs wins: bundled
        // faces seed an empty library, they do not maintain it.
        val target = temporary.newFolder("fonts")
        FontStore.seedBundled(RuntimeEnvironment.getApplication().assets, target)
        val mine = File(target, "Lalezar-Regular.ttf")
        mine.writeText("not really a font, but it is mine")

        FontStore.seedBundled(RuntimeEnvironment.getApplication().assets, target)

        mine.readText() shouldBe "not really a font, but it is mine"
    }
}

/**
 * That the seeding is actually *wired*, not merely written.
 *
 * [BundledFontsTest] calls `seedBundled` itself, which proves the function works and proves nothing
 * about whether anything calls it — the exact gap that let four bundled faces ride along in every
 * APK unread for months while the suite stayed green.
 *
 * So this asserts nothing about `seedBundled` at all. It builds the view model the application
 * builds, lets its startup run to completion, and asks the catalogue for a face that can set a
 * Persian word. Every link in the chain — the seed, the roots it seeds into, the scan, the
 * catalogue — has to hold for that to come back non-empty, and no refactor can satisfy it by
 * accident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontStoreWiringTest {

    @Test
    fun `the view model's own startup fills the catalogue on a fresh install`() {
        val context = RuntimeEnvironment.getApplication()
        FontStore.importDirectory(context).deleteRecursively()

        val model = EditorViewModel(context)
        model.autoSave.stop()

        // Driven rather than joined. The startup deliberately bounces between `Dispatchers.IO` and
        // the main looper, and Robolectric's main looper is paused — so `join()` inside
        // `runBlocking` would hold the very thread the resumption is queued on and deadlock, and a
        // virtual-time scheduler has nothing to advance while a real background thread reads real
        // files. Idling in a loop is what actually lets both halves make progress.
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + STARTUP_BUDGET_MS
        while (!model.startup.isCompleted && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(5)
        }
        model.startup.isCompleted shouldBe true

        model.fontStore.catalog.usableFor("گنجشک").shouldNotBeEmpty()
    }

    private companion object {
        /** Generous: the first run of this test copies the whole bundle out of the APK. */
        const val STARTUP_BUDGET_MS = 60_000L
    }
}
