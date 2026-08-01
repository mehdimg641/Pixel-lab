package ir.pixellab.core.fonts

import ir.pixellab.core.model.FontRef
import ir.pixellab.core.text.FontCapabilities

/** Which writing systems a font can actually render. */
enum class Script { ARABIC, LATIN, BOTH, NEITHER }

/**
 * One font file as read from disk.
 *
 * Produced by the platform-specific scanner; everything below this line is pure logic so it can be
 * tested without a device.
 */
data class FontFile(
    val path: String,
    val family: String,
    val subfamily: String,
    val postScriptName: String,
    val fullName: String,
    /** OS/2 usWeightClass. */
    val weight: Int,
    val italic: Boolean,
    val axes: Map<String, ClosedFloatingPointRange<Float>> = emptyMap(),
    val features: Set<String> = emptySet(),
    val script: Script = Script.NEITHER,
    val hasPersianDigits: Boolean = false,
    val hasTatweel: Boolean = true,
    val glyphCount: Int = 0,
) {
    val isVariable: Boolean get() = axes.isNotEmpty()
    val hasKashidaAxis: Boolean get() = FontRef.KASHIDA_AXES.any { it in axes }

    val capabilities: FontCapabilities
        get() = FontCapabilities(axes = axes, features = features, hasTatweel = hasTatweel)
}

/**
 * A user-facing typeface: every file that belongs to one design, collapsed into a single entry.
 *
 * The supplied library is 312 files but only 12 designs — the rest are weights and `FaNum` / `NoEn`
 * / `Web` / `Mobile` variants of the same faces. Listing files directly makes the picker unusable,
 * so grouping is a functional requirement rather than presentation polish.
 */
data class Typeface(
    val name: String,
    val files: List<FontFile>,
) {
    val weights: List<Int> = files.map { it.weight }.distinct().sorted()
    val variableFile: FontFile? = files.firstOrNull { it.isVariable }
    val hasKashidaAxis: Boolean = files.any { it.hasKashidaAxis }
    val stylisticSets: List<String> =
        files.flatMap { it.features }.filter { it.startsWith("ss") || it == "salt" }.distinct().sorted()
    val script: Script = when {
        files.any { it.script == Script.BOTH } -> Script.BOTH
        files.any { it.script == Script.ARABIC } && files.any { it.script == Script.LATIN } -> Script.BOTH
        files.any { it.script == Script.ARABIC } -> Script.ARABIC
        files.any { it.script == Script.LATIN } -> Script.LATIN
        else -> Script.NEITHER
    }
    val supportsPersianDigits: Boolean = files.any { it.hasPersianDigits }

    /** Sample string for previews, so an Arabic face is never previewed with Latin text. */
    val previewText: String
        get() = when (script) {
            Script.ARABIC -> "آفتاب ۱۳۴"
            Script.LATIN -> "Handgloves 134"
            Script.BOTH -> "آفتاب Handgloves"
            Script.NEITHER -> name
        }

    /** Picks the file closest to [weight], preferring a variable file that can hit it exactly. */
    fun resolve(weight: Int, italic: Boolean = false): FontFile? {
        variableFile?.let { vf ->
            val range = vf.axes[FontRef.AXIS_WEIGHT]
            if (range != null && weight.toFloat() in range) return vf
        }
        val matchingSlant = files.filter { it.italic == italic }.ifEmpty { files }
        return matchingSlant.minByOrNull { kotlin.math.abs(it.weight - weight) }
    }
}

/**
 * Groups font files into typefaces and answers queries against them.
 *
 * Grouping keys on the family name with the known suffix families stripped, because those variants
 * differ only in digit set or Latin coverage — properties the UI exposes as switches instead of as
 * separate entries in the list.
 */
object FontGrouper {

    /**
     * Suffixes that mark a variant of the same design rather than a distinct one. Longest first so
     * `IRANSansXFaNum` strips to `IRANSansX` and not `IRANSansXFa`.
     */
    private val VARIANT_SUFFIXES = listOf(
        "MonoSpacedNum", "OnlyNumeral", "OnlyNumral", "FaNum", "NoEn", "DN",
        "Web", "Mobile", "Small", "Variable", "Adobe", "VF", "Pro", "FA",
    ).sortedByDescending { it.length }

    private val WEIGHT_WORDS = listOf(
        "ExtraBlack", "ExtraBold", "ExtraLight", "UltraLight", "UltraBold", "SemiBold", "DemiBold",
        "Hairline", "Black", "Heavy", "Light", "Medium", "Thin", "Bold", "Fat", "Regular", "Italic",
        "ExtBd", "ExtLt", "SemBd", "Med",
    ).sortedByDescending { it.length }

    fun typefaceKey(family: String): String {
        var name = family.trim()
        // Drop parenthesised qualifiers such as "Kalameh(FaNum)".
        name = name.replace(Regex("""\s*\([^)]*\)"""), "")
        var changed = true
        while (changed) {
            changed = false
            for (word in WEIGHT_WORDS) {
                val stripped = name.replace(Regex("""[\s_-]*$word$""", RegexOption.IGNORE_CASE), "")
                if (stripped != name && stripped.isNotBlank()) {
                    name = stripped.trim(); changed = true
                }
            }
            for (suffix in VARIANT_SUFFIXES) {
                val stripped = name.replace(Regex("""[\s_-]*$suffix$""", RegexOption.IGNORE_CASE), "")
                if (stripped != name && stripped.isNotBlank()) {
                    name = stripped.trim(); changed = true
                }
            }
        }
        return name.trim(' ', '_', '-').ifBlank { family }
    }

    /**
     * Collapses a display name to a comparison key.
     *
     * Spacing and separators are dropped because the same design ships under both `IRANSans` and
     * `IRAN Sans` depending on the file, and users would not accept seeing it twice.
     */
    fun groupKey(family: String): String =
        typefaceKey(family).lowercase().filter { it.isLetterOrDigit() }

    fun group(files: List<FontFile>): List<Typeface> {
        val buckets = files.groupBy { groupKey(it.family) }.toMutableMap()

        // A trailing V or VF marks the variable cut of a face that is otherwise already present
        // (IRANSansXV alongside IRANSansX). Fold it in only when the base really exists, so a name
        // that legitimately ends in V is left alone.
        for (key in buckets.keys.toList()) {
            val base = key.removeSuffix("v")
            if (base != key && base.isNotEmpty() && buckets.containsKey(base)) {
                buckets[base] = buckets.getValue(base) + buckets.getValue(key)
                buckets.remove(key)
            }
        }

        return buckets.values.map { group ->
            Typeface(displayName(group), group.sortedBy { it.weight })
        }.sortedBy { it.name.lowercase() }
    }

    /** The shortest stripped name in the bucket, which is the cleanest form of the design's name. */
    private fun displayName(group: List<FontFile>): String =
        group.map { typefaceKey(it.family) }
            .distinct()
            .minByOrNull { it.length }
            ?: group.first().family
}

/**
 * The font library the editor talks to.
 *
 * Resolution never fails. A document that references a font the user has since deleted or moved
 * opens with the nearest substitute and a warning, because losing a project to a missing file is
 * far worse than losing a typeface.
 */
class FontCatalog(files: List<FontFile>) {

    val typefaces: List<Typeface> = FontGrouper.group(files)

    private val byPostScript: Map<String, FontFile> = files.associateBy { it.postScriptName }
    private val byTypefaceName: Map<String, Typeface> =
        typefaces.associateBy { FontGrouper.groupKey(it.name) }

    val size: Int get() = typefaces.size
    val fileCount: Int = files.size

    /** Case- and script-insensitive search over typeface names and their file names. */
    fun search(query: String): List<Typeface> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return typefaces
        return typefaces.filter { face ->
            face.name.lowercase().contains(q) ||
                face.files.any { it.family.lowercase().contains(q) || it.fullName.lowercase().contains(q) }
        }
    }

    fun byScript(script: Script): List<Typeface> = typefaces.filter {
        it.script == script || (script != Script.BOTH && it.script == Script.BOTH)
    }

    /** Typefaces that can render [text]; keeps a numerals-only font out of a picker for a headline. */
    fun usableFor(text: String): List<Typeface> {
        val needsArabic = ir.pixellab.core.text.ArabicJoining.isArabicScript(text)
        val needsLatin = text.any { it in 'A'..'Z' || it in 'a'..'z' }
        return typefaces.filter { face ->
            val arabicOk = !needsArabic || face.script == Script.ARABIC || face.script == Script.BOTH
            val latinOk = !needsLatin || face.script == Script.LATIN || face.script == Script.BOTH
            arabicOk && latinOk
        }
    }

    fun typefaceOf(ref: FontRef): Typeface? = byTypefaceName[FontGrouper.groupKey(ref.family)]

    /**
     * Resolves [ref] to a concrete file, degrading rather than failing.
     *
     * The order is deliberate: an exact PostScript match, then the same typeface at the nearest
     * weight, then any font of a compatible script. Only a completely empty catalogue returns null.
     */
    fun resolve(ref: FontRef): Resolution {
        ref.postScriptName?.let { ps ->
            byPostScript[ps]?.let { return Resolution(it, FontMatch.EXACT, null) }
        }
        typefaceOf(ref)?.let { face ->
            face.resolve(ref.weight, ref.italic)?.let {
                val exactWeight = it.weight == ref.weight || it.isVariable
                return Resolution(
                    file = it,
                    match = if (exactWeight) FontMatch.SAME_FAMILY else FontMatch.NEAREST_WEIGHT,
                    warning = if (exactWeight) null else
                        "وزن ${ref.weight} در «${face.name}» موجود نیست؛ نزدیک‌ترین وزن (${it.weight}) استفاده شد.",
                )
            }
        }
        val fallback = typefaces.firstOrNull { it.script == Script.BOTH || it.script == Script.ARABIC }
            ?: typefaces.firstOrNull()
        val file = fallback?.resolve(ref.weight, ref.italic) ?: return Resolution(null, FontMatch.MISSING, MISSING)
        return Resolution(
            file = file,
            match = FontMatch.SUBSTITUTED,
            warning = "فونت «${ref.family}» پیدا نشد؛ موقتاً «${fallback.name}» جایگزین شد.",
        )
    }

    private companion object {
        const val MISSING = "هیچ فونتی در دسترس نیست."
    }
}

enum class FontMatch {
    /** The exact file the document asked for. */
    EXACT,

    /** Same typeface, and the requested weight is reachable. */
    SAME_FAMILY,

    /** Same typeface at a different weight. */
    NEAREST_WEIGHT,

    /** A different typeface entirely — the project still opens, with a warning. */
    SUBSTITUTED,

    MISSING,
}

data class Resolution(val file: FontFile?, val match: FontMatch, val warning: String?) {
    val isDegraded: Boolean get() = match == FontMatch.NEAREST_WEIGHT ||
        match == FontMatch.SUBSTITUTED || match == FontMatch.MISSING
}
