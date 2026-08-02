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
