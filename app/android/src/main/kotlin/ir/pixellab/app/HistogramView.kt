package ir.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.unit.dp
import ir.pixellab.core.imaging.Histogram
import ir.pixellab.core.imaging.HistogramChannel

/**
 * The tone distribution, drawn.
 *
 * The one readout that turns judging an image by eye into measuring it. Two things about how it is
 * drawn matter more than they look:
 *
 * The bars are scaled **logarithmically**, because linear scaling makes it useless on any real
 * photograph — one dominant tone, a sky or a studio backdrop, is often a hundred times taller than
 * everything else and flattens the rest to nothing.
 *
 * Clipping is called out in red at each end rather than left to be read off the bars. A screen
 * cannot show the difference between 254 and 255, so blown highlights are invisible in the artwork
 * and visible only here — and only if something draws attention to them.
 */
@Composable
fun HistogramView(
    histogram: Histogram?,
    channel: HistogramChannel,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (histogram == null) {
            Text(
                "یک لایهٔ تصویر انتخاب کنید",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
            )
            return@Column
        }

        val heights = histogram.normalised()
        val tint = when (channel) {
            HistogramChannel.RED -> UiColor(0xFFE05252)
            HistogramChannel.GREEN -> UiColor(0xFF52C46A)
            HistogramChannel.BLUE -> UiColor(0xFF5B8DE0)
            HistogramChannel.ALPHA -> UiColor(0xFF9AA2B1)
            HistogramChannel.LUMINANCE -> UiColor(0xFFD8DCE4)
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(HEIGHT.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.ChromeSunken),
        ) {
            val step = size.width / heights.size
            for (bin in heights.indices) {
                val barHeight = heights[bin] * size.height
                if (barHeight <= 0f) continue
                drawRect(
                    color = tint,
                    // Mirrored: the histogram reads left-to-right dark-to-light in every tool that
                    // has one, and flipping it to follow the interface's direction would make every
                    // piece of advice anyone has ever read about histograms wrong.
                    topLeft = Offset(size.width - (bin + 1) * step, size.height - barHeight),
                    size = Size(step + SEAM, barHeight),
                )
            }
        }

        val total = histogram.total.coerceAtLeast(1L)
        // In tenths of a percent, not whole percent. Clipping worth acting on is routinely a
        // fraction of one per cent — a whole-number percentage rounds every such case to zero and
        // the warning that exists to be seen is never shown at all.
        val blacks = histogram.clippedBlacks * TENTHS / total
        val whites = histogram.clippedWhites * TENTHS / total
        Text(
            buildString {
                append("میانگین ${histogram.mean().toInt()}")
                if (histogram.clippedBlacks > 0) append(" · سایهٔ سوخته ${tenths(blacks)}٪")
                if (histogram.clippedWhites > 0) append(" · روشنایی سوخته ${tenths(whites)}٪")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (histogram.clippedBlacks > 0 || histogram.clippedWhites > 0) {
                Ink.Danger
            } else {
                Ink.TextMuted
            },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Tall enough to read a shape from, short enough to leave the sheet room for controls. */
private const val HEIGHT = 84

/** A hair of overlap, or rounding leaves a gap between neighbouring bars at some widths. */
private const val SEAM = 0.75f

/**
 * Tenths of a percent, rendered with one decimal.
 *
 * Never rounds a real reading down to nothing: any clipping at all shows as at least 0.1%, because
 * the number's job is to say "look here", and "0%" beside a red warning reads as a bug.
 */
private fun tenths(value: Long): String {
    val floored = value.coerceAtLeast(1L)
    return "${floored / 10}.${floored % 10}"
}

private const val TENTHS = 1000L
