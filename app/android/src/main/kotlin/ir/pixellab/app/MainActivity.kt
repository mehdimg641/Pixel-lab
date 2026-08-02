package ir.pixellab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.pixellab.core.editor.LayerBounds
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelLabTheme {
                // The interface is right-to-left throughout. The canvas is not, and cannot be: a
                // design's coordinates have nothing to do with the language of the tool editing it,
                // and mirroring them is the mistake that makes Persianised editors unusable for
                // layout work. EditorCanvas therefore does its own mapping and ignores this.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val model: EditorViewModel = viewModel()
                    EditorScreen(model, SHAPE_BOUNDS)
                }
            }
        }
    }

    private companion object {
        /** Mirrors the view model's measurement until the rasteriser supplies real bounds. */
        val SHAPE_BOUNDS = LayerBounds { layer ->
            when (val geometry = (layer as? Layer.Shape)?.geometry) {
                is ShapeGeometry.Rectangle -> Rect.of(geometry.size)
                is ShapeGeometry.Ellipse -> Rect.of(geometry.size)
                is ShapeGeometry.Polygon -> Rect.of(geometry.size)
                is ShapeGeometry.Star -> Rect.of(geometry.size)
                else -> Rect.of(Vec2(320f, 160f))
            }
        }
    }
}
