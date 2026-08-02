package ir.pixellab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.pixellab.engine.android.PlatformCodecs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything can open a file. Which formats exist is a property of the device — HEIF
        // needs API 28 — so the registry is filled in here rather than declared statically.
        PlatformCodecs.register()
        enableEdgeToEdge()
        setContent {
            PixelLabTheme {
                // The interface is right-to-left throughout. The canvas is not, and cannot be: a
                // design's coordinates have nothing to do with the language of the tool editing it,
                // and mirroring them is the mistake that makes Persianised editors unusable for
                // layout work. EditorCanvas therefore does its own mapping and ignores this.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val model: EditorViewModel = viewModel()
                    EditorScreen(model)
                }
            }
        }
    }

}
