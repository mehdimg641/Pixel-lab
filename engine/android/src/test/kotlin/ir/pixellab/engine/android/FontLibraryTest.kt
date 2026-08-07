package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.FontRef
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scanning is the part the user touches most often and the part most likely to fail quietly.
 *
 * Their fonts arrive as extracted archives with Persian folder names, at varying depths, mixed with
 * licences and preview images. Every one of those has broken a font scanner at some point in this
 * project already — the folder-name encoding did, on the very first run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun realFonts(): List<File> {
        val root = System.getProperty("pixellab.samples")?.let(::File) ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.extension.lowercase() in FontLibrary.FONT_EXTENSIONS }
            .toList()
    }

    @Test
    fun `an empty library resolves to nothing rather than crashing`() {
        val library = FontLibrary(listOf(folder.newFolder("empty")))
        library.rescan()
        library.fontCount shouldBe 0
        // A project opened before any font was added still has to draw something.
        library.resolve(FontRef(family = "Anything")) shouldBe null
    }

    @Test
    fun `a missing root is skipped rather than throwing`() {
        val library = FontLibrary(listOf(File(folder.root, "does-not-exist")))
        library.rescan()
        library.fontCount shouldBe 0
    }

    @Test
    fun `only font files are opened`() {
        val root = folder.newFolder("mixed")
        File(root, "licence.txt").writeText("OFL")
        File(root, "preview.png").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "notafont.ttf").writeBytes(byteArrayOf(0, 0, 0, 0))

        val library = FontLibrary(listOf(root))
        library.rescan()
        // A collection folder is mostly not fonts; opening every file makes a rescan slow enough
        // that the picker stalls on launch.
        library.fontCount shouldBe 0
        library.failed.size shouldBe 1
    }

    @Test
    fun `a font in a persian folder name is found`() {
        val fonts = realFonts()
        assumeTrue("no sample fonts on this machine", fonts.isNotEmpty())

        // This exact case broke the first scan: a default-encoding JVM skipped the folders and
        // reported 204 of 312 fonts with no error at all.
        val nested = File(folder.newFolder("کتابخانه"), "فونت‌های فارسی")
        nested.mkdirs()
        fonts.first().copyTo(File(nested, fonts.first().name))

        val library = FontLibrary(listOf(folder.root))
        library.rescan()
        library.fontCount shouldBe 1
    }

    @Test
    fun `nested collections are found at the depth they actually arrive at`() {
        val fonts = realFonts()
        assumeTrue("no sample fonts on this machine", fonts.isNotEmpty())

        val deep = File(folder.root, "a/b/c/d")
        deep.mkdirs()
        fonts.first().copyTo(File(deep, fonts.first().name))
        val library = FontLibrary(listOf(folder.root))
        library.rescan()
        library.fontCount shouldBe 1
    }

    @Test
    fun `a rescan picks up a font added afterwards`() {
        val fonts = realFonts()
        assumeTrue("no sample fonts on this machine", fonts.size >= 2)

        val root = folder.newFolder("live")
        fonts[0].copyTo(File(root, fonts[0].name))
        val library = FontLibrary(listOf(root))
        library.rescan()
        val before = library.fontCount

        // Adding a font is a normal act in this app, not a setup step.
        fonts[1].copyTo(File(root, fonts[1].name))
        library.rescan()
        (library.fontCount > before) shouldBe true
    }

    @Test
    fun `the order is stable across rescans`() {
        val fonts = realFonts()
        assumeTrue("no sample fonts on this machine", fonts.size >= 3)

        val root = folder.newFolder("stable")
        fonts.take(3).forEach { it.copyTo(File(root, it.name)) }
        val library = FontLibrary(listOf(root))
        val first = library.rescan().typefaces.map { it.name }
        val second = library.rescan().typefaces.map { it.name }
        // A picker that reshuffles under the user's finger after they add one font is worse than
        // one that is slow.
        second shouldBe first
    }

    @Test
    fun `a resolved reference comes back with a usable file`() {
        val fonts = realFonts()
        assumeTrue("no sample fonts on this machine", fonts.isNotEmpty())

        val root = folder.newFolder("resolve")
        fonts.take(5).forEach { it.copyTo(File(root, it.name)) }
        val library = FontLibrary(listOf(root))
        val catalog = library.rescan()
        assumeTrue("no typefaces parsed", catalog.typefaces.isNotEmpty())

        val name = catalog.typefaces.first().name
        library.resolve(FontRef(family = name))!!.family.isNotBlank() shouldBe true
        // And an unknown family degrades to a substitute rather than to nothing.
        val substituted = library.resolve(FontRef(family = "این فونت وجود ندارد"))
        (substituted != null) shouldBe true
        (library.warningFor(FontRef(family = "این فونت وجود ندارد")) != null) shouldBe true
    }
}
