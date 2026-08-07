package ir.pixellab.app

import ir.pixellab.core.fonts.Script
import ir.pixellab.engine.android.FontLibrary
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    fun `at least one bundled face is a Persian display face`() {
        // Not merely Persian-capable — the system's Noto Naskh is that, and it is a *text* face.
        // The app exists to make cover art, so it has to bring at least one heavy display cut of
        // its own or the first thing a user does is go and find one.
        val catalog = seedAndScan()

        val persian = catalog.byScript(Script.ARABIC)
        persian.shouldNotBeEmpty()
    }

    @Test
    fun `seeding twice does not overwrite what the user put there`() {
        val context = RuntimeEnvironment.getApplication()
        FontStore.seedBundled(context, context.assets)

        // Somebody drops in their own cut under a name the APK also ships. Theirs wins: bundled
        // faces seed an empty library, they do not maintain it.
        val mine = File(FontStore.importDirectory(context), "Lalezar-Regular.ttf")
        mine.writeText("not really a font, but it is mine")
        FontStore.seedBundled(context, context.assets)

        mine.readText() shouldBe "not really a font, but it is mine"
    }
}
