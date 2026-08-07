package ir.pixellab.engine.android

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.graphics.fonts.FontVariationAxis
import android.os.Build
import androidx.annotation.RequiresApi
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.FontRef
import java.io.File

/**
 * Builds platform typefaces, applying variable-font axes.
 *
 * The axis path is the whole point: six of the supplied fonts expose a `KASH` axis, and driving it
 * is what makes elongation continuous while leaving the user's text untouched. Android exposes this
 * through `Typeface.Builder.setFontVariationSettings`, which needs API 26 — the reason `minSdk` is
 * pinned there.
 */
class TypefaceLoader(private val cacheLimit: Int = 64) {

    /** Keyed on file plus resolved axis values, because two axis settings are two typefaces. */
    private val cache = object : LinkedHashMap<String, Typeface>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean =
            size > cacheLimit
    }

    fun load(file: FontFile, variations: Map<String, Float> = emptyMap()): Typeface? {
        val key = cacheKey(file.path, variations)
        cache[key]?.let { return it }

        val typeface = build(file, variations) ?: return null
        cache[key] = typeface
        return typeface
    }

    /** Applies only the axes the font actually declares, clamped to their declared ranges. */
    fun resolveVariations(file: FontFile, requested: Map<String, Float>): Map<String, Float> =
        requested.mapNotNull { (tag, value) ->
            val range = file.axes[tag] ?: return@mapNotNull null
            tag to value.coerceIn(range.start, range.endInclusive)
        }.toMap()

    fun clear() = cache.clear()

    val cacheSize: Int get() = cache.size

    private fun build(file: FontFile, variations: Map<String, Float>): Typeface? {
        val source = File(file.path)
        if (!source.isFile) return null
        val applied = resolveVariations(file, variations)

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && applied.isNotEmpty()) {
                buildWithFontApi(source, applied, file)
            } else {
                Typeface.Builder(source)
                    .apply { if (applied.isNotEmpty()) setFontVariationSettings(format(applied)) }
                    .build()
            }
        }.getOrNull()
    }

    /**
     * The `android.graphics.fonts` builder, used from API 29 upwards.
     *
     * `Typeface.Builder` silently ignores variation settings on some vendor implementations; going
     * through `Font.Builder` sets the axes on the font instance itself, which is honoured
     * everywhere.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildWithFontApi(source: File, applied: Map<String, Float>, file: FontFile): Typeface {
        val axes = applied.map { (tag, value) -> FontVariationAxis(tag, value) }.toTypedArray()
        val weight = applied[FontRef.AXIS_WEIGHT]?.toInt() ?: file.weight
        val font = Font.Builder(source)
            .setFontVariationSettings(axes)
            .setWeight(weight.coerceIn(FontStyle.FONT_WEIGHT_MIN, FontStyle.FONT_WEIGHT_MAX))
            .setSlant(if (file.italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
            .build()
        return Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
    }

    companion object {
        /** CSS-style axis list, the format both Android builders accept. */
        fun format(variations: Map<String, Float>): String =
            variations.entries.sortedBy { it.key }.joinToString(", ") { (tag, value) ->
                "'$tag' ${trimZeros(value)}"
            }

        /** OpenType feature string for `Paint.fontFeatureSettings`. */
        fun formatFeatures(features: Map<String, Int>): String =
            features.entries.sortedBy { it.key }.joinToString(", ") { (tag, value) -> "'$tag' $value" }

        internal fun cacheKey(path: String, variations: Map<String, Float>): String =
            if (variations.isEmpty()) path else "$path|${format(variations)}"

        private fun trimZeros(v: Float): String {
            val asInt = v.toInt()
            return if (v == asInt.toFloat()) asInt.toString() else v.toString()
        }
    }
}
