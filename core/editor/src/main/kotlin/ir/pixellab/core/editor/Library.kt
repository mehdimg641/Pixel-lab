package ir.pixellab.core.editor

import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.GradientType
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ParagraphStyle
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.TextAlign
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import kotlinx.serialization.Serializable

/**
 * A saved effect stack, applicable to any layer.
 *
 * Photoshop's Styles panel, and the reason the whole effect stack is a plain ordered list: a style
 * *is* that list, so saving one is storing the value rather than serialising a special case.
 */
@Serializable
data class StylePreset(
    val name: String,
    val style: Style,
    /**
     * A stable ASCII handle, separate from the display name.
     *
     * The name is Persian and meant to be read; this is meant to be *written* — into a file name,
     * into a saved document that refers to a preset, into a test's output directory. Deriving one
     * from the other is not possible in either direction: a slug of Persian text is a row of
     * dashes, and a translation back is a guess. So both are stored, and renaming the visible one
     * never breaks anything that referred to it.
     */
    val id: String = "",
)

/**
 * A starting document.
 *
 * Sized for what this app is for rather than for a generic list of paper sizes: covers, posts and
 * stories, at the pixel dimensions the platforms actually want.
 */
@Serializable
data class TemplatePreset(
    val name: String,
    val width: Int,
    val height: Int,
    /** What the canvas is for, so the picker can group without a second field per entry. */
    val group: String,
)

/**
 * The built-in library.
 *
 * These are deliberately *starting points*, not finished designs. A template that arrives already
 * composed is one the user has to dismantle before it is theirs, and the styles below are the ones a
 * Persian cover actually needs — heavy outlines that survive a busy photograph, a gold that reads as
 * metal rather than as yellow, and a shadow soft enough not to fight the type.
 */
object Library {

    val styles: List<StylePreset> = listOf(
        /**
         * The cover title this application was built to make — the lower of its two layers.
         *
         * This look is two layers and cannot be one. The frame's stroke has to sit *outside* the
         * letters and the face's gradient *inside* them, and a single stroke cannot be on two sides
         * at once — which is exactly why every Photoshop recipe for these titles begins by
         * duplicating the text layer. `Library.coverProject` assembles the pair; these two entries
         * are here so either half can be applied on its own.
         *
         * The lower half carries the thick gold border, the block thrown down and right, and the
         * two shadows under the whole word.
         */
        StylePreset(
            name = "جلد سه‌بعدی — قاب طلایی",
            id = "cover-frame",
            style = Style(
                fill = Fill.Solid(Color.WHITE),
                effects = listOf(
                    // Two shadows: one tight to seat the word on the page, one wide for the depth
                    // of the room behind it. A single shadow can be either and reads as a sticker.
                    Effect.DropShadow(color = Color(0.04f, 0.03f, 0.02f), angle = 125f, distance = 10f, blur = 5f, opacity = 0.60f),
                    Effect.DropShadow(color = Color(0.06f, 0.05f, 0.04f), angle = 125f, distance = 35f, blur = 25f, opacity = 0.30f),
                    Effect.Extrude(
                        steps = 60,
                        stepOffset = Vec2(0.9f, 0.9f),
                        nearFill = Fill.Solid(Color(0.98f, 0.62f, 0.13f)),
                        farFill = Fill.Solid(Color(0.38f, 0.14f, 0.03f)),
                    ),
                    // The border, ramped orange to gold, and a chiselled edge on it. Without the
                    // bevel the frame is a flat band of colour; with it the border reads as a bent
                    // strip of metal, which is what the reference actually shows.
                    Effect.Bevel(
                        technique = ir.pixellab.core.model.BevelTechnique.CHISEL_HARD,
                        depth = 220f,
                        size = 6f,
                        angle = 125f,
                        altitude = 38f,
                        highlightColor = Color(1f, 0.96f, 0.78f),
                        highlightOpacity = 0.8f,
                        shadowColor = Color(0.35f, 0.16f, 0.02f),
                        shadowOpacity = 0.6f,
                    ),
                    Effect.Stroke(
                        width = 20f,
                        position = ir.pixellab.core.model.StrokePosition.OUTSIDE,
                        fill = Fill.Gradient(
                            stops = listOf(
                                GradientStop(0f, Color(0.902f, 0.494f, 0.133f)),
                                GradientStop(1f, Color(0.945f, 0.769f, 0.059f)),
                            ),
                            angle = 90f,
                        ),
                    ),
                ),
            ),
        ),

        /**
         * The upper half: the painted teal face, its inset edge and the fine gold line round it.
         *
         * The asset id names a texture that ships inside the application. If it is missing the
         * pattern simply does not paint and the rest of the style still applies — a style that
         * refused to load because one of its six parts was absent would be worse than one that
         * arrives a shade flatter.
         */
        StylePreset(
            name = "جلد سه‌بعدی — چهرهٔ فیروزه‌ای",
            id = "cover-face",
            style = Style(
                fill = Fill.Solid(Color.WHITE),
                effects = listOf(
                    Effect.Overlay(
                        fill = Fill.Gradient(
                            type = GradientType.LINEAR,
                            stops = listOf(
                                GradientStop(0f, Color(0.051f, 0.231f, 0.275f)),
                                GradientStop(0.55f, Color(0f, 0.659f, 0.588f)),
                                GradientStop(1f, Color(0.878f, 0.624f, 0.404f)),
                            ),
                            angle = 45f,
                        ),
                    ),
                    // Over the ramp and blended rather than replacing it: Overlay at a little over
                    // half keeps the gradient's light and dark and lets the pattern only disturb
                    // them, which is what makes it read as pigment on a surface rather than as a
                    // photograph pasted into the letters.
                    Effect.Overlay(
                        fill = Fill.Pattern(asset = ir.pixellab.core.model.AssetId("bundled:paint-teal")),
                        blendMode = ir.pixellab.core.model.BlendMode.OVERLAY,
                        opacity = 0.6f,
                    ),
                    Effect.InnerShadow(color = Color(0.06f, 0.16f, 0.18f), angle = 125f, distance = 5f, blur = 16f, opacity = 0.55f),
                    Effect.Bevel(
                        depth = 160f,
                        size = 13f,
                        angle = 125f,
                        altitude = 42f,
                        highlightColor = Color(1f, 0.97f, 0.86f),
                        highlightOpacity = 0.7f,
                        shadowColor = Color(0.10f, 0.20f, 0.22f),
                        shadowOpacity = 0.5f,
                    ),
                    Effect.Stroke(width = 5f, fill = Fill.Solid(Color(1f, 0.82f, 0.24f))),
                ),
            ),
        ),

        StylePreset(
            name = "تیتر سفید با دور مشکی",
            id = "headline-outline",
            style = Style(
                fill = Fill.Solid(Color.WHITE),
                effects = listOf(
                    // Outside, not centred: a centred stroke eats into the letterform, and Persian
                    // faces have thin joins that a centred stroke closes up entirely.
                    Effect.Stroke(width = 10f, fill = Fill.Solid(Color.BLACK), position = ir.pixellab.core.model.StrokePosition.OUTSIDE),
                    Effect.DropShadow(blur = 20f, distance = 6f, opacity = 0.55f),
                ),
            ),
        ),

        StylePreset(
            name = "طلایی",
            id = "gold",
            style = Style(
                fill = Fill.Gradient(
                    type = GradientType.LINEAR,
                    angle = 90f,
                    stops = listOf(
                        GradientStop(0f, Color(0.45f, 0.31f, 0.08f)),
                        GradientStop(0.35f, Color(1f, 0.84f, 0.42f)),
                        GradientStop(0.5f, Color(1f, 0.97f, 0.78f)),
                        GradientStop(0.62f, Color(0.93f, 0.72f, 0.28f)),
                        GradientStop(1f, Color(0.4f, 0.26f, 0.06f)),
                    ),
                ),
                effects = listOf(
                    // The bright band in the middle is what makes gold read as metal; a plain
                    // yellow-to-brown ramp reads as sand.
                    Effect.Bevel(depth = 140f, size = 6f, soften = 2f),
                    Effect.Stroke(width = 4f, fill = Fill.Solid(Color(0.28f, 0.18f, 0.04f))),
                    Effect.DropShadow(blur = 16f, distance = 5f, opacity = 0.6f),
                ),
            ),
        ),

        StylePreset(
            name = "برجستهٔ سه‌بعدی",
            id = "extruded-3d",
            style = Style(
                fill = Fill.Solid(Color(0.95f, 0.24f, 0.32f)),
                effects = listOf(
                    // Steps and a per-step offset rather than a depth and an angle: that is what
                    // the reference PSDs are actually doing when they duplicate a smart object
                    // twenty-nine times, and it is why one editable layer can replace all of them.
                    Effect.Extrude(
                        steps = 14,
                        stepOffset = Vec2(-2f, 2f),
                        nearFill = Fill.Solid(Color(0.72f, 0.12f, 0.2f)),
                        farFill = Fill.Solid(Color(0.42f, 0.05f, 0.12f)),
                    ),
                    Effect.Stroke(width = 6f, fill = Fill.Solid(Color.WHITE)),
                    Effect.DropShadow(blur = 26f, distance = 14f, opacity = 0.5f),
                ),
            ),
        ),

        StylePreset(
            name = "نئون",
            id = "neon",
            style = Style(
                fill = Fill.Solid(Color(1f, 1f, 1f)),
                effects = listOf(
                    Effect.OuterGlow(blur = 26f, fill = Fill.Solid(Color(0.24f, 0.85f, 1f)), opacity = 0.9f),
                    Effect.OuterGlow(blur = 60f, fill = Fill.Solid(Color(0.1f, 0.45f, 1f)), opacity = 0.6f),
                    Effect.InnerGlow(blur = 6f, fill = Fill.Solid(Color(0.6f, 0.95f, 1f))),
                ),
            ),
        ),

        StylePreset(
            name = "توخالی",
            id = "hollow",
            style = Style(
                fill = Fill.Solid(Color.WHITE),
                // Fill opacity, not layer opacity: dropping the fill to nothing leaves every effect
                // at full strength, which is the whole trick.
                fillOpacity = 0f,
                effects = listOf(Effect.Stroke(width = 5f, fill = Fill.Solid(Color.WHITE))),
            ),
        ),

        StylePreset(
            name = "شیشه‌ای",
            id = "glass",
            style = Style(
                fill = Fill.Backdrop(blurRadius = 18f, saturation = 1.2f, brightness = 1.05f),
                effects = listOf(
                    Effect.Stroke(width = 2f, fill = Fill.Solid(Color(1f, 1f, 1f, 0.6f))),
                    Effect.InnerGlow(blur = 12f, fill = Fill.Solid(Color(1f, 1f, 1f, 0.4f))),
                ),
            ),
        ),

        /**
         * Chrome.
         *
         * Three ingredients, and leaving any one out gives grey plastic. The fill is a five-stop
         * vertical ramp with a *horizon* in it — dark, white, mid, white, dark — because a polished
         * surface reflects the sky above and the ground below and the join between them is the band
         * across the middle of every chrome letter ever made. The gloss contour rings, putting a
         * second highlight below the first. And the bevel is chiselled rather than smooth, because
         * chrome has an edge and a smooth shoulder rounds it away.
         */
        StylePreset(
            name = "کروم",
            id = "chrome",
            style = Style(
                fill = Fill.Gradient(
                    type = GradientType.LINEAR,
                    angle = 90f,
                    stops = listOf(
                        GradientStop(0f, Color(0.05f, 0.05f, 0.06f)),
                        GradientStop(0.3f, Color(1f, 1f, 1f)),
                        GradientStop(0.5f, Color(0.23f, 0.24f, 0.27f)),
                        GradientStop(0.68f, Color(0.94f, 0.95f, 0.97f)),
                        GradientStop(1f, Color(0.11f, 0.11f, 0.13f)),
                    ),
                ),
                effects = listOf(
                    Effect.Bevel(
                        technique = ir.pixellab.core.model.BevelTechnique.CHISEL_HARD,
                        depth = 250f,
                        size = 5f,
                        angle = 90f,
                        altitude = 16f,
                        useGlobalLight = false,
                        glossContour = ir.pixellab.core.model.Curve.RING,
                        highlightOpacity = 1f,
                        shadowOpacity = 0.85f,
                    ),
                    Effect.InnerGlow(
                        blur = 18f,
                        fill = Fill.Solid(Color.WHITE),
                        blendMode = BlendMode.SCREEN,
                        opacity = 0.6f,
                    ),
                    Effect.InnerShadow(angle = 90f, distance = 3f, blur = 8f, opacity = 0.75f),
                    Effect.Stroke(width = 2f, fill = Fill.Solid(Color(0.08f, 0.08f, 0.1f))),
                    Effect.DropShadow(blur = 18f, distance = 8f, opacity = 0.55f),
                ),
            ),
        ),

        /**
         * Brushed metal.
         *
         * Chrome without the horizon: one soft ramp, grain along it, and satin to give the sheen a
         * direction. The grain is the whole difference — polished metal reflects, brushed metal
         * scatters, and scattering is noise.
         */
        StylePreset(
            name = "فلز کشیده",
            id = "brushed-metal",
            style = Style(
                fill = Fill.Gradient(
                    type = GradientType.LINEAR,
                    angle = 90f,
                    stops = listOf(
                        GradientStop(0f, Color(0.32f, 0.33f, 0.36f)),
                        GradientStop(0.45f, Color(0.78f, 0.79f, 0.82f)),
                        GradientStop(1f, Color(0.26f, 0.27f, 0.3f)),
                    ),
                ),
                effects = listOf(
                    Effect.Noise(amount = 0.09f, scale = 0.35f, monochrome = true),
                    Effect.Satin(
                        color = Color(0.85f, 0.87f, 0.92f),
                        angle = 20f,
                        distance = 14f,
                        blur = 20f,
                        blendMode = BlendMode.SCREEN,
                        opacity = 0.35f,
                    ),
                    Effect.Bevel(depth = 120f, size = 4f, soften = 1f, altitude = 55f),
                    Effect.DropShadow(blur = 14f, distance = 6f, opacity = 0.5f),
                ),
            ),
        ),

        /**
         * Fire.
         *
         * Built from the outside in, which is the order flame actually has: a wide red halo, a
         * tighter orange one inside it, and a yellow-white core. The silhouette is roughened because
         * a flame has no clean edge, and the roughening is what stops this reading as orange text
         * with a glow behind it.
         */
        StylePreset(
            name = "آتش",
            id = "fire",
            style = Style(
                fill = Fill.Gradient(
                    type = GradientType.LINEAR,
                    angle = 90f,
                    stops = listOf(
                        GradientStop(0f, Color(0.55f, 0.05f, 0f)),
                        GradientStop(0.5f, Color(1f, 0.45f, 0.02f)),
                        GradientStop(1f, Color(1f, 0.93f, 0.55f)),
                    ),
                ),
                effects = listOf(
                    Effect.OuterGlow(blur = 55f, fill = Fill.Solid(Color(0.8f, 0.07f, 0f)), opacity = 0.75f),
                    Effect.OuterGlow(blur = 22f, fill = Fill.Solid(Color(1f, 0.5f, 0.05f)), opacity = 0.9f),
                    Effect.InnerGlow(blur = 10f, fill = Fill.Solid(Color(1f, 0.95f, 0.7f)), opacity = 0.85f),
                    Effect.EdgeRoughen(amount = 3f, detail = 0.65f, seed = 7),
                ),
            ),
        ),

        /**
         * Glitch.
         *
         * A channel offset and nothing else doing the work. The temptation is to add noise and a
         * scanline and a shake, and the result reads as damage rather than as a signal — one clean
         * separation of red and blue is what a broken feed actually looks like.
         */
        StylePreset(
            name = "گلیچ",
            id = "glitch",
            style = Style(
                fill = Fill.Solid(Color(0.96f, 0.96f, 0.98f)),
                effects = listOf(
                    Effect.ChromaticOffset(
                        redOffset = Vec2(-7f, 1f),
                        blueOffset = Vec2(7f, -1f),
                    ),
                    Effect.Stroke(width = 1.5f, fill = Fill.Solid(Color(0.05f, 0.05f, 0.07f))),
                ),
            ),
        ),

        /**
         * Graffiti.
         *
         * Two strokes, not one: a thick black outline with a coloured one inside it, which is how
         * every piece on a wall is actually built and why a single outline reads as a sticker. The
         * extrusion is short and hard because spray paint has no soft shadow.
         */
        StylePreset(
            name = "گرافیتی",
            id = "graffiti",
            style = Style(
                fill = Fill.Gradient(
                    type = GradientType.LINEAR,
                    angle = 75f,
                    stops = listOf(
                        GradientStop(0f, Color(0.98f, 0.78f, 0.08f)),
                        GradientStop(0.55f, Color(0.95f, 0.35f, 0.1f)),
                        GradientStop(1f, Color(0.8f, 0.1f, 0.35f)),
                    ),
                ),
                effects = listOf(
                    Effect.Extrude(
                        steps = 10,
                        stepOffset = Vec2(2f, 3f),
                        nearFill = Fill.Solid(Color(0.07f, 0.06f, 0.09f)),
                        farFill = Fill.Solid(Color(0.07f, 0.06f, 0.09f)),
                    ),
                    Effect.Stroke(width = 12f, fill = Fill.Solid(Color(0.05f, 0.04f, 0.06f))),
                    Effect.Stroke(width = 5f, fill = Fill.Solid(Color.WHITE)),
                    Effect.EdgeRoughen(amount = 1.5f, detail = 0.35f, seed = 13),
                ),
            ),
        ),

        /**
         * Glitter.
         *
         * Coloured rather than monochrome noise, and that is the point: glitter is thousands of tiny
         * facets each catching the light at its own angle, so the sparkle has hue. Monochrome grain
         * over a gradient reads as a dirty print.
         */
        StylePreset(
            name = "اکلیل",
            id = "glitter",
            style = Style(
                fill = Fill.Gradient(
                    type = GradientType.LINEAR,
                    angle = 60f,
                    stops = listOf(
                        GradientStop(0f, Color(0.75f, 0.25f, 0.62f)),
                        GradientStop(0.5f, Color(0.98f, 0.72f, 0.85f)),
                        GradientStop(1f, Color(0.45f, 0.3f, 0.78f)),
                    ),
                ),
                effects = listOf(
                    Effect.Noise(amount = 0.55f, scale = 0.12f, monochrome = false, blendMode = BlendMode.SCREEN),
                    Effect.Bevel(depth = 200f, size = 3f, altitude = 65f, highlightOpacity = 0.9f),
                    Effect.OuterGlow(blur = 24f, fill = Fill.Solid(Color(1f, 0.8f, 0.95f)), opacity = 0.5f),
                    Effect.DropShadow(blur = 20f, distance = 7f, opacity = 0.45f),
                ),
            ),
        ),

        /**
         * Eighties retro.
         *
         * The extrusion runs down-right into a magenta-to-violet fade rather than into a darker copy
         * of the face, because the look comes from the sunset behind the letters and not from
         * lighting. The chrome-blue face over it is the other half.
         */
        StylePreset(
            name = "رترو هشتاد",
            id = "retro-eighties",
            style = Style(
                fill = Fill.Gradient(
                    type = GradientType.LINEAR,
                    angle = 90f,
                    stops = listOf(
                        GradientStop(0f, Color(0.09f, 0.13f, 0.42f)),
                        GradientStop(0.48f, Color(0.62f, 0.9f, 1f)),
                        GradientStop(0.52f, Color(1f, 0.98f, 0.9f)),
                        GradientStop(1f, Color(0.95f, 0.35f, 0.6f)),
                    ),
                ),
                effects = listOf(
                    Effect.Extrude(
                        steps = 28,
                        stepOffset = Vec2(1.2f, 1.6f),
                        nearFill = Fill.Solid(Color(0.93f, 0.18f, 0.55f)),
                        farFill = Fill.Solid(Color(0.3f, 0.06f, 0.42f)),
                    ),
                    Effect.Stroke(width = 4f, fill = Fill.Solid(Color(1f, 0.95f, 0.98f))),
                    Effect.OuterGlow(blur = 40f, fill = Fill.Solid(Color(0.95f, 0.2f, 0.6f)), opacity = 0.55f),
                ),
            ),
        ),

        /**
         * Cyberpunk.
         *
         * Neon's opposite arrangement: a dark face with the light *behind* it rather than a bright
         * face glowing outwards. Cyan and magenta at different radii because the two never sit at
         * the same distance in the reference work, and the channel split sells the screen it is
         * supposedly being displayed on.
         */
        StylePreset(
            name = "سایبرپانک",
            id = "cyberpunk",
            style = Style(
                fill = Fill.Solid(Color(0.06f, 0.07f, 0.11f)),
                effects = listOf(
                    Effect.OuterGlow(blur = 48f, fill = Fill.Solid(Color(0.9f, 0.08f, 0.62f)), opacity = 0.7f),
                    Effect.OuterGlow(blur = 18f, fill = Fill.Solid(Color(0.1f, 0.95f, 0.95f)), opacity = 0.85f),
                    Effect.Stroke(width = 2.5f, fill = Fill.Solid(Color(0.35f, 1f, 1f))),
                    Effect.InnerGlow(blur = 14f, fill = Fill.Solid(Color(0.1f, 0.8f, 0.9f)), opacity = 0.5f),
                    Effect.ChromaticOffset(redOffset = Vec2(-2.5f, 0f), blueOffset = Vec2(2.5f, 0f)),
                ),
            ),
        ),

        StylePreset(
            name = "سایهٔ بلند",
            id = "long-shadow",
            style = Style(
                fill = Fill.Solid(Color(0.15f, 0.16f, 0.2f)),
                effects = listOf(
                    // A long shadow is an extrusion, not a blur: a blurred shadow at this distance
                    // is a smudge, and the hard-edged version is what the style is recognised by.
                    Effect.Extrude(
                        steps = 60,
                        stepOffset = Vec2(-1.5f, 1.5f),
                        nearFill = Fill.Solid(Color(0f, 0f, 0f, 0.18f)),
                        farFill = Fill.Solid(Color(0f, 0f, 0f, 0.18f)),
                    ),
                ),
            ),
        ),
    )

    val templates: List<TemplatePreset> = listOf(
        TemplatePreset("پست اینستاگرام", 1080, 1080, "شبکهٔ اجتماعی"),
        TemplatePreset("استوری", 1080, 1920, "شبکهٔ اجتماعی"),
        TemplatePreset("کاور یوتیوب", 1280, 720, "ویدیو"),
        TemplatePreset("کاور پادکست", 3000, 3000, "صوت"),
        TemplatePreset("بنر تلگرام", 1280, 720, "شبکهٔ اجتماعی"),
        TemplatePreset("پوستر A4", 2480, 3508, "چاپ"),
        TemplatePreset("کارت ویزیت", 1063, 638, "چاپ"),
        TemplatePreset("بوم مربع کوچک", 800, 800, "عمومی"),
    )

    /**
     * A blank document at a template's size, with a title already on it.
     *
     * With a layer rather than empty: an empty canvas gives the user nothing to select, nothing to
     * style, and no idea what the tools do. One editable headline is the shortest path from opening
     * the app to seeing it work.
     *
     * **Plain black, and no layer style.** It used to arrive wearing `headline-outline` — white
     * letters under a ten-pixel black stroke and a black drop shadow — on the theory that a
     * decorated example shows more of what the application can do. What it actually showed was a
     * mass of black on a white canvas, and a user's first question was what the shape under their
     * word was. Two things follow from that and both are worth stating: the first thing on screen
     * has to be the thing the user recognises, and a starter document is not a showroom. The
     * styles are a tap away in the style panel, chosen rather than inflicted — and being *chosen*
     * is also what makes them read as a feature rather than as a defect.
     */
    fun documentFor(template: TemplatePreset, id: String = "new"): Document = Document(
        id = DocumentId(id),
        canvas = CanvasSpec(template.width, template.height, background = Fill.Solid(Color.WHITE)),
        name = template.name,
        layers = listOf(
            Layer.Text(
                id = LayerId("title"),
                spec = TextSpec(
                    text = "عنوان",
                    font = FontRef(family = "", weight = 700),
                    // A fifteenth of the canvas height: large enough to read on a phone thumbnail,
                    // which is where most of this work is actually seen.
                    size = template.height / 15f,
                    paragraph = ParagraphStyle(align = TextAlign.CENTER),
                ),
                name = "عنوان",
                transform = Transform(
                    translation = Vec2(template.width * 0.1f, template.height * 0.4f),
                ),
                style = Style.PLAIN_BLACK,
                blendMode = BlendMode.NORMAL,
            ),
        ),
    )

    /**
     * The cover treatment as a finished project, not as a style to apply.
     *
     * A style is one layer's effect stack, and this look is not one layer. The frame's stroke has to
     * sit *outside* the letters and the face's gradient *inside* them, and one stroke cannot be on
     * two sides at once — which is exactly why the Photoshop recipes for these titles all begin by
     * duplicating the text layer. A preset cannot express that; a project can.
     *
     * Three layers, bottom to top: the ground, the frame, the face. The two text layers are linked,
     * so moving or editing one moves the other and the pair cannot drift apart.
     *
     * @param text the headline. Persian or Latin — the styles carry no assumption either way.
     */
    fun coverProject(
        template: TemplatePreset = templates.first { it.name == "کاور پادکست" },
        text: String = "طرح",
        id: String = "cover",
    ): Document {
        val size = Vec2(template.width.toFloat(), template.height.toFloat())
        val title = TextSpec(
            text = text,
            font = FontRef(family = "", weight = 900),
            size = template.height / 5f,
            paragraph = ParagraphStyle(align = TextAlign.CENTER),
        )
        // Leaned rather than rotated, and the difference is the whole look: rotating a title
        // foreshortens its face in the same movement, and these covers keep their letters frontal
        // while the block runs off at an angle. That is a shear.
        val lean = Transform(
            translation = Vec2(size.x * 0.08f, size.y * 0.42f),
            skew = Vec2(-8f, 0f),
        )

        return Document(
            id = DocumentId(id),
            canvas = CanvasSpec(template.width, template.height, background = Fill.Solid(Color.WHITE)),
            name = "جلد سه‌بعدی",
            layers = listOf(
                groundLayer(size),
                Layer.Text(
                    id = LayerId("cover-frame"),
                    spec = title,
                    name = "قاب",
                    transform = lean,
                    style = styleNamed("cover-frame"),
                ),
                Layer.Text(
                    id = LayerId("cover-face"),
                    spec = title,
                    name = "چهره",
                    transform = lean,
                    style = styleNamed("cover-face"),
                ),
            ),
            links = ir.pixellab.core.model.LinkGroups(
                groups = listOf(setOf(LayerId("cover-frame"), LayerId("cover-face"))),
            ),
        )
    }

    /**
     * The grained, vignetted ground the recipe puts these titles on.
     *
     * The vignette is a **radial gradient fill**, not a darkening filter. A filter would bake the
     * shading into pixels, and the whole point of the ground is that a user drags its centre or
     * changes its two greys without redoing anything above it. A radial ramp from a light middle to
     * a darker edge is what a vignette *is*; there is no reason for it to be destructive.
     *
     * The grain is not decoration either. A flat grey at this size bands visibly on a phone screen —
     * eight bits across a slow ramp is a step every few pixels — and a little noise breaks the
     * banding up. Photoshop's own recipes add it for the same reason.
     */
    private fun groundLayer(size: Vec2) = Layer.Shape(
        id = LayerId("cover-ground"),
        geometry = ir.pixellab.core.model.ShapeGeometry.Rectangle(size),
        name = "زمینه",
        style = Style(
            fill = Fill.Gradient(
                type = GradientType.RADIAL,
                stops = listOf(
                    GradientStop(0f, Color(0.898f, 0.898f, 0.906f)),
                    GradientStop(0.62f, Color(0.827f, 0.827f, 0.839f)),
                    GradientStop(1f, Color(0.639f, 0.639f, 0.659f)),
                ),
                // Past the corners, so the darkest stop is reached at the frame's edge rather than
                // short of it — a vignette that finishes early reads as a circle drawn on the page.
                scale = 1.45f,
            ),
            effects = listOf(Effect.Noise(amount = 0.022f, scale = 1f, monochrome = true)),
        ),
    )

    private fun styleNamed(id: String) =
        styles.first { it.id == id }.style
}
