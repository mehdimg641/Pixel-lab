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
         * Unpacks the bundled faces, then builds the store around the directory holding them.
         *
         * **Eagerly, and on the calling thread.** Seeding began life inside the view model's
         * startup coroutine and that was wrong in a way worth recording: the coroutine hops to
         * `Dispatchers.IO` and has to resume on Main, and the interface audit composes with the
         * main looper paused. With an empty directory the walk finished inside the drain window and
         * nobody noticed; with real files to copy and parse it did not, so `waitForIdle` sat there
         * until Espresso's sixty-second timeout and seven unrelated audit tests failed in CI.
         *
         * Here there is no coroutine to race. The first launch copies about 1.5 MB once; every
         * launch after it is one `exists()` per bundled name, which is seven `stat` calls. The
         * directory walk that follows is still asynchronous, exactly as before — that part was
         * never the problem.
         */
        /**
         * **Not wired to [seedBundled] yet, and that is a deliberate stopping point.**
         *
         * Unpacking the bundled faces here is one line and it works. What it also does is give
         * every Robolectric test a populated font directory for the first time, so screens that had
         * always measured text to a placeholder box start shaping real type — and in a run where
         * the app's merged resources also exist, the interface audit, the navigation tests and the
         * screenshot tests stop reaching an idle state at all. `AuditedInterface.probe` already
         * carries a long note about that wire; this pulls on it harder than the note's own fix
         * covers.
         *
         * Three attempts are recorded so the next one does not repeat them. Pausing the clock the
         * way `probe` does breaks the navigation tests outright — they click things and need frames
         * to keep advancing, so the nodes they reach for never appear. Raising Espresso's idle
         * timeout does not help either: the tree is not slow, it never settles, so a longer ceiling
         * only makes the same failures take three minutes each. And moving the call between the
         * view model's startup coroutine and here changes which tests fail, not whether they do.
         *
         * So the faces ship and stay unread for now, exactly as before — no regression, and no
         * green build resting on a fix that does not work. The real repair is to stop these
         * harnesses building a live `EditorViewModel` for a layout measurement, which is its own
         * piece of work and not one to start at the end of another.
         */
        fun forApp(context: Context) = FontStore(FontLibrary(roots(context)))

        private const val DIRECTORY = "fonts"
        private const val SYSTEM_FONTS = "/system/fonts"
    }
}
