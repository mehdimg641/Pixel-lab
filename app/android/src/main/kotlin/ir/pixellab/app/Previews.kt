package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.imaging.Histogram
import ir.pixellab.core.imaging.HistogramChannel

/**
 * Previews for the controls that can be rendered without an editor behind them.
 *
 * Only the leaf controls, and that limit is the point rather than a shortfall. A preview of a whole
 * sheet would need a view model, a font scan and an asset store, so it would be a preview of a
 * scaffold built for the preview — which tells nobody anything about the real screen. These are the
 * pieces that genuinely have no dependencies, and they are also the ones whose spacing and states
 * are worth looking at side by side.
 *
 * Every preview is wrapped right-to-left, because the interface is Persian and a control that reads
 * correctly left-to-right can be quietly wrong the other way round — a slider whose label and value
 * swap ends, a chip row that starts from the wrong edge.
 */
@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    PixelLabTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxWidth().background(Ink.ChromeRaised)) { content() }
        }
    }
}

@Preview(name = "کنترل‌های شیت", showBackground = true, backgroundColor = 0xFF1E1E1E)
@Composable
private fun SheetControlsPreview() {
    PreviewFrame {
        var value by remember { mutableStateOf(0.4f) }
        var chosen by remember { mutableStateOf(1) }

        SheetSection("بخش")
        SheetHint("توضیح کوتاهی که زیر کنترل می‌آید")
        SheetChips {
            for (i in 0..3) {
                SheetChip("گزینهٔ $i", chosen = chosen == i) { chosen = i }
            }
        }
        SheetSlider("مقدار", value, 0f..1f, onChange = { next, _ -> value = next })
        SheetAction("انجام بده") {}
        SheetAction("غیرفعال", enabled = false) {}
    }
}

@Preview(name = "ویرایشگر گرادیان", showBackground = true, backgroundColor = 0xFF1E1E1E, heightDp = 620)
@Composable
private fun GradientEditorPreview() {
    PreviewFrame {
        var gradient by remember {
            mutableStateOf(
                Fill.Gradient(
                    stops = listOf(
                        GradientStop(0f, Color(0.05f, 0.10f, 0.35f)),
                        GradientStop(0.55f, Color(0.85f, 0.30f, 0.45f)),
                        GradientStop(1f, Color(1f, 0.80f, 0.35f)),
                    ),
                ),
            )
        }
        GradientEditorBody(gradient = gradient, onChange = { gradient = it })
    }
}

@Preview(name = "هیستوگرام", showBackground = true, backgroundColor = 0xFF1E1E1E)
@Composable
private fun HistogramPreview() {
    PreviewFrame {
        // A plausible photograph rather than a flat ramp: the logarithmic scaling and the clipping
        // warning are both invisible on uniform data, which would make the preview useless for
        // judging the only two things about this control that are hard to get right.
        val counts = IntArray(Histogram.BINS) { bin ->
            val body = kotlin.math.exp(-((bin - 96f) * (bin - 96f)) / 900f) * 4200f
            val shoulder = kotlin.math.exp(-((bin - 190f) * (bin - 190f)) / 260f) * 900f
            (body + shoulder).toInt()
        }
        counts[0] = 600
        counts[Histogram.BINS - 1] = 240
        HistogramView(Histogram(counts, HistogramChannel.LUMINANCE), HistogramChannel.LUMINANCE)
    }
}

@Preview(name = "انتخابگر رنگ", showBackground = true, backgroundColor = 0xFF1E1E1E, heightDp = 460)
@Composable
private fun ColorPickerPreview() {
    PreviewFrame {
        var color by remember { mutableStateOf(Color(0.85f, 0.30f, 0.45f)) }
        ColorPickerBody(color = color, onChange = { color = it })
    }
}
