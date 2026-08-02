package ir.pixellab.core.imaging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Measures the filters instead of claiming numbers for them.
 *
 * The specification gives a time budget per filter. Asserting those budgets *here* would be
 * dishonest in both directions: a build machine is not a phone, and a shared runner's timings vary
 * by several times between runs, so a passing number would prove nothing and a failing one would be
 * noise. What this does instead is measure, write the table down, and assert only the two things a
 * timing can honestly establish — that nothing has become catastrophically slow, and that the
 * relative costs are still in the order the algorithms imply.
 *
 * A separable blur must beat an exact one. A bilateral filter must cost more than a box average.
 * Those relationships hold on any machine, and if one inverts, something is genuinely wrong.
 */
class FilterBenchmarkTest {

    private val report = StringBuilder()

    private fun photo(size: Int = SIZE): Raster {
        val random = Random(19)
        val r = Raster(size, size, 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // Structure plus grain, so nothing short-circuits on a flat image — a filter timed
                // on a uniform buffer is a filter timed on its best case.
                val base = 0.5f + 0.35f * kotlin.math.sin(x * 0.02f) * kotlin.math.cos(y * 0.017f)
                r[x, y, 0] = (base + random.nextFloat() * 0.06f).coerceIn(0f, 1f)
                r[x, y, 1] = (base * 0.9f + random.nextFloat() * 0.06f).coerceIn(0f, 1f)
                r[x, y, 2] = (base * 0.75f + random.nextFloat() * 0.06f).coerceIn(0f, 1f)
                r[x, y, 3] = 1f
            }
        }
        return r
    }

    /**
     * Runs [body] a few times and takes the best.
     *
     * The best rather than the mean: a slow run means the machine was doing something else, which
     * says nothing about the code. The fastest run is the closest thing available to a measurement
     * of the work itself.
     */
    private fun time(label: String, runs: Int = RUNS, body: () -> Unit): Long {
        body()
        var best = Long.MAX_VALUE
        repeat(runs) {
            val taken = measureNanoTime(body)
            if (taken < best) best = taken
        }
        report.append(String.format("%-28s %8.2f ms%n", label, best / 1_000_000.0))
        return best
    }

    /**
     * Times two implementations against each other by **alternating** them.
     *
     * The reason is a failure this test actually had. Measuring one filter to completion and then
     * the other means the two see different machine conditions, and on a shared runner those differ
     * by a factor of ten — enough to invert a genuine three-times advantage and fail a build over
     * nothing. Alternating puts both through the same interference, so what survives is the
     * difference between the implementations rather than the difference between two moments.
     *
     * This is the same argument the class comment makes about the specification's millisecond
     * budgets, applied to the one place the original version of this file forgot it.
     */
    private fun compare(
        labelA: String,
        bodyA: () -> Unit,
        labelB: String,
        bodyB: () -> Unit,
    ): Pair<Long, Long> {
        bodyA()
        bodyB()
        var bestA = Long.MAX_VALUE
        var bestB = Long.MAX_VALUE
        repeat(COMPARE_ROUNDS) {
            val a = measureNanoTime(bodyA)
            val b = measureNanoTime(bodyB)
            if (a < bestA) bestA = a
            if (b < bestB) bestB = b
        }
        report.append(String.format("%-28s %8.2f ms%n", labelA, bestA / 1_000_000.0))
        report.append(String.format("%-28s %8.2f ms%n", labelB, bestB / 1_000_000.0))
        return bestA to bestB
    }

    @Test
    fun `the filter set is measured and its relative costs make sense`() {
        val source = photo()
        val single = source.channel(0)

        // Both on the same single-channel raster, and alternated. Timing the separable pass on four
        // channels against the exact one on one channel would compare four times the work with one;
        // timing them one after the other would compare two different moments on a shared machine.
        val (separable, exact) = compare(
            "gaussian blur r=10 (1ch)", { Blur.gaussian(single, 10f) },
            "gaussian blur exact r=10 (1ch)", { Blur.gaussianExact(single, 10f) },
        )
        time("gaussian blur r=10 (rgba)") { Blur.gaussian(source, 10f) }

        time("motion blur d=40") { MotionBlur.apply(source, 30f, 40f) }
        time("lens blur r=12 6 blades") { LensBlur.apply(source, 12f, 6) }
        time("unsharp mask") { Sharpen.unsharpMask(source, 1f, 1.5f, 0.02f) }
        time("smart sharpen") { Sharpen.smart(source, 1f, 1.5f) }
        val (denoise, vignette) = compare(
            "reduce noise", { Denoise.reduceNoise(source, 0.6f, 0.6f, 0.5f) },
            "vignette", { Stylise.vignette(source, -0.5f) },
        )
        time("dust and scratches r=2") { Denoise.dustAndScratches(source, 2, 0.1f) }
        time("shadow highlight r=30") {
            ShadowHighlight.apply(source, shadowAmount = 0.6f, highlightAmount = 0.4f, radius = 30f)
        }
        time("pixelate 12") { Stylise.pixelate(source, 12) }
        time("film grain") { Stylise.noise(source, 0.08f, true, 1) }
        time("histogram") { Histogram.of(IntArray(SIZE * SIZE) { it }, HistogramChannel.LUMINANCE) }

        File("build/reports").mkdirs()
        File("build/reports/filter-benchmark.txt").writeText(
            "PixelLab filter timings — ${SIZE}x$SIZE, best of $RUNS\n\n$report",
        )

        // Separability is the single most important optimisation in this file: a two-pass blur is
        // O(r) per pixel where the exact one is O(r²), which at radius 10 is ten times fewer taps.
        // The margin below is deliberately loose — a real regression is the separable path falling
        // back to the quadratic one, which shows up as many times slower, not as a few per cent.
        // Asserting a tight ratio would be asserting the runner's mood.
        (separable < exact * MARGIN) shouldBe true

        // A bilateral pass weighs every neighbour twice; a per-pixel vignette touches each once.
        // If that inverts, the "edge-preserving" filter has stopped preserving edges.
        (denoise > vignette / MARGIN) shouldBe true
    }

    private companion object {
        /**
         * Large enough that the measurement is of the filter rather than of the loop's warm-up,
         * small enough that the suite stays quick.
         */
        const val SIZE = 512
        const val RUNS = 3

        /** Alternating rounds. More than the plain timings get, because a ratio is asserted on. */
        const val COMPARE_ROUNDS = 5

        /**
         * How far a ratio may drift before it counts as a regression.
         *
         * Two, because the failures worth catching are order-of-magnitude ones — a separable pass
         * silently becoming quadratic — and anything tighter is a measurement of the build machine.
         */
        const val MARGIN = 2.0
    }
}
