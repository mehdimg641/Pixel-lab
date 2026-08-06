package ir.pixellab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ir.pixellab.engine.android.PlatformCodecs

class MainActivity : ComponentActivity() {

    private var model: EditorViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything can open a file. Which formats exist is a property of the device — HEIF
        // needs API 28 — so the registry is filled in here rather than declared statically.
        PlatformCodecs.register()
        // Made on every launch, not on first use. An empty folder with the right name sitting in the
        // file manager is documentation that cannot get out of date — and a user told to "put the
        // model somewhere" with no folder to put it in has been told nothing.
        AssetKind.entries.forEach { it.directoryIn(this) }
        setContent {
            // The view model is resolved *before* the theme, because the theme reads a preference
            // off it. Resolving it inside the theme instead — which is where it used to sit — meant
            // the choice could not reach the palette, so a light theme existed in the code and
            // nothing in the interface could ask for it.
            val editor: EditorViewModel = viewModel()
            model = editor

            val dark = when (editor.preferences.theme) {
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            }

            // The system bars follow the choice too, and that is not a detail. `enableEdgeToEdge()`
            // with no arguments derives the bars' polarity from the *phone's* dark setting, so a
            // user who asked for a light interface on a dark phone got white icons on the light
            // chrome — unreadable — and dark bars top and bottom whatever they picked. Called here
            // rather than before `setContent` because it has to re-run when the preference changes.
            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }

            PixelLabTheme(skin = editor.preferences.skin, dark = dark) {
                // The interface is right-to-left throughout. The canvas is not, and cannot be: a
                // design's coordinates have nothing to do with the language of the tool editing it,
                // and mirroring them is the mistake that makes Persianised editors unusable for
                // layout work. EditorCanvas therefore does its own mapping and ignores this.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PixelLabApp(editor)
                }
            }
        }
    }

    /**
     * The recovery write that catches most real losses.
     *
     * A process is killed while it is *not* in front of the user far more often than while it is,
     * so the moment of being backgrounded is worth more than any number of timer ticks. The timer
     * covers the rest.
     */
    override fun onStop() {
        super.onStop()
        val editor = model ?: return
        if (editor.autoSave.dirty) {
            lifecycleScope.launch { editor.autoSave.writeNow() }
        }
    }
}
