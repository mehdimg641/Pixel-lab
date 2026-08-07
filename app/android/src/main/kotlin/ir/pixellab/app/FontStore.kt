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
            val target = importDirectory(context)
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

        fun forApp(context: Context) = FontStore(FontLibrary(roots(context)))

        private const val DIRECTORY = "fonts"
        private const val SYSTEM_FONTS = "/system/fonts"
    }
}
