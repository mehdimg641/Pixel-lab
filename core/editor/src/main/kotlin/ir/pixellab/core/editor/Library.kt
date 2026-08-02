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
data class StylePreset(val name: String, val style: Style)

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
        StylePreset(
            name = "تیتر سفید با دور مشکی",
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
            style = Style(
                fill = Fill.Backdrop(blurRadius = 18f, saturation = 1.2f, brightness = 1.05f),
                effects = listOf(
                    Effect.Stroke(width = 2f, fill = Fill.Solid(Color(1f, 1f, 1f, 0.6f))),
                    Effect.InnerGlow(blur = 12f, fill = Fill.Solid(Color(1f, 1f, 1f, 0.4f))),
                ),
            ),
        ),

        StylePreset(
            name = "سایهٔ بلند",
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
                style = styles.first().style,
                blendMode = BlendMode.NORMAL,
            ),
        ),
    )
}
