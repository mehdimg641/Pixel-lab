package ir.pixellab.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.fonts.FontCatalog
import ir.pixellab.engine.android.FontLibrary
import ir.pixellab.engine.android.FontResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The scanned font library, as Compose state.
 *
 * Adding a font is a normal act in this app rather than a setup step, so this is built around
 * rescanning: the walk runs off the main thread, and its result is published as a *new* resolver.
 * The canvas compares resolver identity to decide whether to re-shape its text layers, so replacing
 * the instance is what makes a font added mid-session actually appear.
 *
 * Until the first scan finishes the resolver is [FontResolver.NONE] and text layers measure to their
 * placeholder box. That is deliberate: launching has to be instant on a card holding three hundred
 * fonts, and a blocking scan on the main thread is how a font picker becomes a five-second splash.
 */
class FontStore(private val library: FontLibrary) {

    /** Replaced wholesale on each scan, so the canvas can see the change by identity. */
    var resolver: FontResolver by mutableStateOf(FontResolver.NONE)
        private set

    /** Typefaces grouped for the picker: 312 files are 12 designs, and listing files is unusable. */
    var catalog: FontCatalog by mutableStateOf(FontCatalog(emptyList()))
        private set

    /** Files that looked like fonts and would not parse, so the picker can name them. */
    var failed: List<String> by mutableStateOf(emptyList())
        private set

    var scanning: Boolean by mutableStateOf(false)
        private set

    suspend fun rescan() {
        scanning = true
        try {
            val fresh = withContext(Dispatchers.IO) { library.rescan() }
            catalog = fresh
            failed = library.failed
            resolver = FontResolver.of(fresh)
        } finally {
            scanning = false
        }
    }

    companion object {

        /** Where fonts the user imports through the app are kept. */
        fun importDirectory(context: Context): File =
            File(context.filesDir, DIRECTORY).apply { mkdirs() }

        /**
         * Copies the faces bundled in the APK into the import directory, once.
         *
         * ### Why this function has to exist
         *
         * [FontLibrary] walks `File` roots, and an entry inside an APK is not a file — it is a
         * stream out of `AssetManager`. So the four display faces that have been shipping in
         * `assets/fonts/` since the beginning were **never once loaded**: 478 KB in every APK,
         * present in no font list, reachable by no code path. `AssetStore.loadBundled` is the only
         * reader of bundled assets and it takes the `textures/` folder alone. The one thing that
         * ever referenced these names is a test fixture, and it loads its own copy out of
         * `src/test/resources/`.
         *
         * Unpacking them to `filesDir` is what makes them real, and it costs one copy on first
         * launch. The alternative — teaching `FontLibrary` to walk assets — would put an Android
         * type into a module that is deliberately plain `File`, to save a copy nobody notices.
         *
         * ### Once, and never over the user
         *
         * A name already present is skipped. Somebody who dropped a better cut of Vazirmatn into
         * the folder keeps theirs: bundled faces seed an empty library, they do not maintain it.
         */
        fun seedBundled(context: Context, assets: android.content.res.AssetManager) {
            seedBundled(assets, importDirectory(context))
        }

        /**
         * The same, into a directory the caller names.
         *
         * Exists for tests, and it earned its place the hard way. The version that could only write
         * to `filesDir` meant a test asserting "a face already there is not overwritten" had to put
         * a junk file *in the real font directory* — which Robolectric shares across the JVM, so
         * every later test that scanned fonts choked on it and spun the composition until the idle
         * timeout. Six unrelated tests failed, in CI, pointing at the interface audit.
         *
         * A seam that lets a test keep its mess to itself is cheaper than the afternoon that costs.
         */
        fun seedBundled(assets: android.content.res.AssetManager, target: File) {
            target.mkdirs()
            val names = runCatching { assets.list(DIRECTORY) }.getOrNull().orEmpty()
            for (name in names) {
                val file = File(target, name)
                if (file.exists()) continue
                runCatching {
                    assets.open("$DIRECTORY/$name").use { input ->
                        file.outputStream().use(input::copyTo)
                    }
                }.onFailure {
                    // A face that will not unpack is a face the picker simply will not list. It is
                    // not worth failing a launch over, and `FontLibrary.failed` already exists to
                    // name anything that makes it to disk and then will not parse.
                    file.delete()
                }
            }
        }

        /**
         * The three places a font can come from.
         *
         * The external directory is the one that matters to this user: it is visible from any file
         * manager with no permission at all, so a folder of Persian faces can be copied in and
         * picked up by a rescan without the app being involved in the transfer.
         *
         * The system directory is included so that text draws on a fresh install. A first launch
         * where every text layer is blank reads as a broken editor, not as an empty library.
         */
        fun roots(context: Context): List<File> = buildList {
            add(importDirectory(context))
            context.getExternalFilesDir(null)?.let { add(File(it, DIRECTORY).apply { mkdirs() }) }
            add(File(SYSTEM_FONTS))
        }

        /**
         * The library the app runs on: the three roots, and nothing read yet.
         *
         * Deliberately cheap, because this is a property initialiser on a view model and therefore
         * runs before the first frame. [seedBundled] is *not* called here — it is the first thing
         * `EditorViewModel.startup` does on `Dispatchers.IO`, alongside the scan it feeds.
         *
         * That split is the whole fix, and it is worth recording why the obvious alternative is
         * worse. Seeding here would put twenty-eight megabytes of copying in front of a cold
         * launch. It would also run in every test that builds a view model to measure a touch
         * target — even the ones that immediately call `stopBackgroundWork`, because a constructor
         * cannot be cancelled. Those tests would then each pay for a copy they never look at, and
         * the only way to stop them would be deleting the directory in `@Before`, which just makes
         * the next test copy it all back.
         *
         * Inside the coroutine it is cancellable, so switching the background work off switches
         * this off too, which is what "off" should have meant all along.
         */
        fun forApp(context: Context): FontStore = FontStore(FontLibrary(roots(context)))

        private const val DIRECTORY = "fonts"
        private const val SYSTEM_FONTS = "/system/fonts"
    }
}
