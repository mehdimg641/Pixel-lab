package ir.pixellab.core.codec

import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2

/** A converted PSD: the document, plus the pixels its layers refer to. */
data class ImportedPsd(
    val document: Document,
    val assets: Map<String, RasterImage>,
    /** What could not be carried across, in the user's own terms rather than in format terms. */
    val warnings: List<String>,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Turns a parsed PSD into a document this app can edit.
 *
 * The reader recovers the file's structure; this decides what that structure *means* here. The two
 * are separate because they fail differently: a reader that cannot parse a block should say so, and
 * an importer that meets a feature this app has no equivalent for should carry the layer across
 * anyway and say what was lost.
 *
 * Nothing is silently dropped. A layer whose effect has no counterpart still arrives with its
 * pixels, its position, its opacity and its blend mode, and the warning names it — because an
 * import that quietly loses a drop shadow is one the user only discovers after exporting.
 */
object PsdImport {

    fun convert(psd: PsdDocument, name: String = "PSD"): ImportedPsd {
        val assets = LinkedHashMap<String, RasterImage>()
        val warnings = ArrayList(psd.warnings)
        val layers = buildTree(psd.layers, assets, warnings)

        return ImportedPsd(
            document = Document(
                id = DocumentId("psd-${psd.width}x${psd.height}"),
                canvas = CanvasSpec(psd.width, psd.height),
                name = name,
                layers = layers,
            ),
            assets = assets,
            warnings = warnings,
        )
    }

    /**
     * Rebuilds the group tree.
     *
     * Photoshop stores groups as a flat list with markers: a divider opens one and a separate entry
     * closes it, and the list runs bottom to top. Walking it forwards and treating the markers as
     * layers is the classic import bug — every group's contents end up as siblings and the whole
     * arrangement of the file is lost.
     */
    private fun buildTree(
        source: List<PsdLayer>,
        assets: MutableMap<String, RasterImage>,
        warnings: MutableList<String>,
    ): List<Layer> {
        val stack = ArrayDeque<MutableList<Layer>>()
        stack.addLast(ArrayList())
        val names = ArrayDeque<PsdLayer>()

        for ((index, psd) in source.withIndex()) {
            when {
                // The closing marker comes *first* in the file, because the list is bottom-up.
                psd.isGroupEnd -> {
                    stack.addLast(ArrayList())
                    names.addLast(psd)
                }
                psd.isGroupStart -> {
                    val children = stack.removeLastOrNull() ?: ArrayList()
                    names.removeLastOrNull()
                    val group = Layer.Group(
                        id = LayerId("psd-$index"),
                        children = children,
                        name = psd.name.ifBlank { "گروه" },
                        opacity = psd.opacity,
                        blendMode = psd.blendMode,
                        visible = psd.visible,
                        locked = psd.locked,
                        clipped = psd.clipping,
                        // Photoshop writes "pass" for a pass-through group and a real blend key
                        // for an isolating one, which is the only place the distinction is stored.
                        passThrough = psd.blendKey.trim() == PASS_THROUGH,
                        expanded = psd.isGroupOpen,
                        style = styleOf(psd, warnings),
                    )
                    stack.lastOrNull()?.add(group)
                }
                else -> stack.lastOrNull()?.add(convertLayer(psd, index, assets, warnings))
            }
        }
        return stack.firstOrNull()?.toList() ?: emptyList()
    }

    private fun convertLayer(
        psd: PsdLayer,
        index: Int,
        assets: MutableMap<String, RasterImage>,
        warnings: MutableList<String>,
    ): Layer {
        val id = LayerId("psd-$index")
        val position = Transform(translation = Vec2(psd.bounds.left.toFloat(), psd.bounds.top.toFloat()))
        val style = styleOf(psd, warnings)

        psd.text?.let { text ->
            // A text layer arrives as a string plus a transform; the font, size and colour live in
            // the engine data, which this build does not yet parse. Carrying the string is what
            // makes the layer editable at all, and losing it makes an import worthless for the very
            // files this app exists for.
            warnings += "«${psd.name}»: قلم و اندازهٔ متن از PSD خوانده نشد؛ متن قابل ویرایش است"
            return Layer.Text(
                id = id,
                spec = TextSpec(text = text.text, font = FontRef(family = "")),
                name = psd.name.ifBlank { text.text.take(24) },
                transform = position,
                opacity = psd.opacity,
                blendMode = psd.blendMode,
                visible = psd.visible,
                locked = psd.locked,
                clipped = psd.clipping,
                style = style,
            )
        }

        val pixels = rasterOf(psd)
        if (pixels != null) {
            val asset = "psd-$index"
            assets[asset] = pixels
            return Layer.Image(
                id = id,
                asset = AssetId(asset),
                name = psd.name.ifBlank { "لایه" },
                transform = position,
                opacity = psd.opacity,
                blendMode = psd.blendMode,
                visible = psd.visible,
                locked = psd.locked,
                clipped = psd.clipping,
                style = style,
            )
        }

        // A layer with no pixels of its own is usually a fill or an adjustment. Bringing it in as an
        // empty shape keeps its position in the stack, which matters because everything above it is
        // clipped or blended relative to where it sits.
        warnings += "«${psd.name}»: پیکسلی نداشت و به‌صورت لایهٔ خالی وارد شد"
        return Layer.Shape(
            id = id,
            geometry = ir.pixellab.core.model.ShapeGeometry.Rectangle(
                Vec2(psd.bounds.width.toFloat().coerceAtLeast(1f), psd.bounds.height.toFloat().coerceAtLeast(1f)),
            ),
            name = psd.name.ifBlank { "لایه" },
            transform = position,
            opacity = psd.opacity,
            blendMode = psd.blendMode,
            visible = psd.visible,
            locked = psd.locked,
            clipped = psd.clipping,
            style = style.copy(fill = Fill.Solid(Color.TRANSPARENT)),
        )
    }

    /**
     * Assembles a layer's channels into an image.
     *
     * Photoshop stores each channel separately and identifies them by id, with -1 for transparency
     * and -2 for the layer mask. Assuming they arrive in order is what produces the classic
     * red-and-blue-swapped import.
     */
    private fun rasterOf(psd: PsdLayer): RasterImage? {
        val width = psd.bounds.width
        val height = psd.bounds.height
        if (width <= 0 || height <= 0) return null

        val red = psd.channels.firstOrNull { it.channel.id == CHANNEL_RED } ?: return null
        val green = psd.channels.firstOrNull { it.channel.id == CHANNEL_GREEN }
        val blue = psd.channels.firstOrNull { it.channel.id == CHANNEL_BLUE }
        val alpha = psd.channels.firstOrNull { it.channel.id == CHANNEL_ALPHA }
        if (red.samples.isEmpty()) return null

        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = red.samples.getOrElse(i) { 0 }.toInt() and 0xFF
            val g = green?.samples?.getOrElse(i) { 0 }?.toInt()?.and(0xFF) ?: r
            val b = blue?.samples?.getOrElse(i) { 0 }?.toInt()?.and(0xFF) ?: r
            // No alpha channel means an opaque layer, which is how a flattened background arrives.
            val a = alpha?.samples?.getOrElse(i) { -1 }?.toInt()?.and(0xFF) ?: 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RasterImage(width, height, pixels)
    }

    /**
     * Maps Photoshop's effects onto this app's.
     *
     * Where a parameter has no counterpart the effect still arrives with its defaults rather than
     * being skipped: a drop shadow at the wrong distance is a thing the user can fix in one drag,
     * and a missing drop shadow is a thing they have to notice first.
     */
    private fun styleOf(psd: PsdLayer, warnings: MutableList<String>): Style {
        if (psd.effects.isEmpty()) return Style.PLAIN_BLACK.copy(fillOpacity = psd.fillOpacity)

        val effects = ArrayList<Effect>()
        for (effect in psd.effects) {
            if (!effect.enabled) continue
            val converted = when (effect.key.trim()) {
                "dropShadow", "DrSh" -> Effect.DropShadow(
                    blur = number(effect, "blur") ?: 12f,
                    distance = number(effect, "Dstn") ?: 8f,
                    angle = number(effect, "lagl") ?: 120f,
                    spread = (number(effect, "Ckmt") ?: 0f) / 100f,
                    color = colorOf(effect) ?: Color.BLACK,
                    opacity = (number(effect, "Opct") ?: 75f) / 100f,
                )
                "innerShadow", "IrSh" -> Effect.InnerShadow(
                    blur = number(effect, "blur") ?: 8f,
                    distance = number(effect, "Dstn") ?: 5f,
                    angle = number(effect, "lagl") ?: 120f,
                    color = colorOf(effect) ?: Color.BLACK,
                    opacity = (number(effect, "Opct") ?: 75f) / 100f,
                )
                "outerGlow", "OrGl" -> Effect.OuterGlow(
                    blur = number(effect, "blur") ?: 10f,
                    fill = Fill.Solid(colorOf(effect) ?: Color.WHITE),
                    opacity = (number(effect, "Opct") ?: 75f) / 100f,
                )
                "innerGlow", "IrGl" -> Effect.InnerGlow(
                    blur = number(effect, "blur") ?: 10f,
                    fill = Fill.Solid(colorOf(effect) ?: Color.WHITE),
                    opacity = (number(effect, "Opct") ?: 75f) / 100f,
                )
                "frameFX", "FrFX" -> Effect.Stroke(
                    width = number(effect, "Sz  ") ?: 3f,
                    fill = Fill.Solid(colorOf(effect) ?: Color.BLACK),
                    opacity = (number(effect, "Opct") ?: 100f) / 100f,
                )
                "bevelEmboss", "ebbl" -> Effect.Bevel(
                    depth = (number(effect, "srgR") ?: 100f) / 100f,
                    size = number(effect, "blur") ?: 5f,
                    angle = number(effect, "lagl") ?: 120f,
                )
                "solidFill", "SoFi" -> Effect.Overlay(
                    fill = Fill.Solid(colorOf(effect) ?: Color.BLACK),
                    opacity = (number(effect, "Opct") ?: 100f) / 100f,
                )
                "chFX", "Sfct" -> Effect.Satin(
                    blur = number(effect, "blur") ?: 14f,
                    distance = number(effect, "Dstn") ?: 11f,
                    angle = number(effect, "lagl") ?: 19f,
                    color = colorOf(effect) ?: Color.BLACK,
                )
                else -> {
                    warnings += "«${psd.name}»: افکت ${effect.key.trim()} معادلی ندارد و وارد نشد"
                    null
                }
            }
            converted?.let(effects::add)
        }
        return Style.PLAIN_BLACK.copy(effects = effects, fillOpacity = psd.fillOpacity)
    }

    private fun number(effect: PsdEffect, key: String): Float? = when (val value = effect.values[key]) {
        is PsdValue.Number -> value.value.toFloat()
        is PsdValue.Unit -> value.value.toFloat()
        else -> null
    }

    /**
     * Pulls a colour out of an effect's descriptor.
     *
     * Photoshop nests it: the effect holds a colour descriptor holding red, green and blue as
     * doubles from 0 to 255. Reading the effect's own numbers instead gives whatever happened to be
     * stored first, which is usually the opacity.
     */
    private fun colorOf(effect: PsdEffect): Color? {
        val colour = effect.values["Clr "] as? PsdValue.Descriptor ?: return null
        fun channel(key: String) = (colour.fields[key] as? PsdValue.Number)?.value?.toFloat()?.div(255f)
        val r = channel("Rd  ") ?: return null
        val g = channel("Grn ") ?: return null
        val b = channel("Bl  ") ?: return null
        return Color(r, g, b)
    }

    private const val PASS_THROUGH = "pass"
    private const val CHANNEL_RED = 0
    private const val CHANNEL_GREEN = 1
    private const val CHANNEL_BLUE = 2
    private const val CHANNEL_ALPHA = -1
}
