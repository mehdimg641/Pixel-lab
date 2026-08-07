package ir.pixellab.app

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * That a brush pack dropped in the folder actually reaches the picker.
 *
 * ### The defect this closes, and why a codec test would not have caught it
 *
 * `AbrCodecTest` proves the parser reads `.abr`. It proves nothing about whether anything calls it —
 * and for `AssetKind.BRUSHES` the answer was no. The kind has advertised `png` and `abr` since it
 * was written, the settings screen names the folder and creates it, and **no code read either**. A
 * user who put a brush pack there got no tips and no error.
 *
 * That is the same gap that let four bundled fonts ride along in every APK unread while the suite
 * stayed green, and it is why this test goes through `EditorViewModel.loadImportedTips` — the thing
 * the picker calls — rather than through the codec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrushImportTest {

    private val width = 4
    private val height = 3

    /** A ramp, so a mask read upside down or transposed is a different picture. */
    private val coverage = ByteArray(width * height) { (it * 20).toByte() }

    private fun brushDirectory(): File =
        checkNotNull(AssetKind.BRUSHES.directoryIn(RuntimeEnvironment.getApplication()))

    @Before
    fun emptyTheFolder() {
        // Robolectric shares the external files directory across the JVM, so a pack left by an
        // earlier test would be counted by this one. Stating the environment rather than inheriting
        // it — the same lesson the font seeding taught this suite the hard way.
        brushDirectory().deleteRecursively()
        brushDirectory().mkdirs()
    }

    /** One uncompressed sampled tip in a version 2 container. */
    private fun abr(name: String): ByteArray {
        fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
        fun ByteArrayOutputStream.u16(v: Int) { u8(v shr 8); u8(v) }
        fun ByteArrayOutputStream.i32(v: Int) { u16(v shr 16); u16(v) }

        val block = ByteArrayOutputStream().apply {
            i32(0)
            u16(25)
            i32(name.length)
            for (character in name) u16(character.code)
            u8(0)
            repeat(4) { u16(0) }
            i32(0); i32(0); i32(height); i32(width)
            u16(8)
            u8(0)
            write(coverage)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            u16(2); u16(1); u16(2)
            i32(block.size)
            write(block)
        }.toByteArray()
    }

    private fun model(): EditorViewModel =
        EditorViewModel(RuntimeEnvironment.getApplication()).also { it.stopBackgroundWork() }

    @Test
    fun `a pack in the folder reaches the picker`() {
        File(brushDirectory(), "pack.abr").writeBytes(abr("مو"))

        val model = model()
        runBlocking { model.loadImportedTips() }

        model.importedTips.shouldNotBeEmpty()
        model.importedTips.single().name shouldBe "مو"
    }

    @Test
    fun `the tip is registered where the rasteriser will look for it`() {
        // The half that a "did it find the file" assertion misses entirely: the picker can list a
        // tip whose pixels were never put in the store, and the stroke then paints nothing.
        File(brushDirectory(), "pack.abr").writeBytes(abr("مو"))

        val model = model()
        runBlocking { model.loadImportedTips() }
        val asset = model.importedTips.single().asset

        val image = model.assetStore.source.load(asset)
        image shouldNotBe null
        image!!.width shouldBe width
        image.height shouldBe height
        // Stored as white with coverage in alpha, exactly as a generated tip is — the rasteriser
        // reads alpha and ignores colour, so a mask stored as grey paints at the wrong strength.
        (image.pixels[5] ushr 24) shouldBe (coverage[5].toInt() and 0xFF)
    }

    @Test
    fun `choosing an imported tip actually switches the brush onto it`() {
        File(brushDirectory(), "pack.abr").writeBytes(abr("مو"))

        val model = model()
        runBlocking { model.loadImportedTips() }
        val asset = model.importedTips.single().asset
        model.useImportedTip(asset)

        (model.paint.preset.tip as? ir.pixellab.core.paint.BrushTip.Sampled)?.asset shouldBe asset
    }

    @Test
    fun `scanning twice does not duplicate the tips`() {
        // The ids are derived from the file name and the index precisely so a rescan is idempotent.
        // Without that, opening the panel five times gives the store five copies of every tip and
        // a preset saved in a document points at whichever copy happened to be first.
        File(brushDirectory(), "pack.abr").writeBytes(abr("مو"))

        val model = model()
        runBlocking { model.loadImportedTips() }
        val first = model.importedTips.map { it.asset }
        runBlocking { model.loadImportedTips() }

        model.importedTips.map { it.asset } shouldBe first
    }

    @Test
    fun `one unreadable pack costs that pack and not the others`() {
        // The ordinary case: these files come off the internet in bulk and a truncated download is
        // common. Failing the whole scan over one would hide the ninety-nine that are fine.
        File(brushDirectory(), "good.abr").writeBytes(abr("خوب"))
        File(brushDirectory(), "broken.abr").writeBytes(byteArrayOf(0, 99, 0, 1, 2, 3))

        val model = model()
        runBlocking { model.loadImportedTips() }

        model.importedTips.map { it.name } shouldBe listOf("خوب")
    }

    @Test
    fun `an empty folder is not an error`() {
        val model = model()
        runBlocking { model.loadImportedTips() }

        model.importedTips shouldBe emptyList()
    }
}
