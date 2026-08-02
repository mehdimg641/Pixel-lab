package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Layer

/**
 * The layer itself: how it blends, how solid it is, and what it is clipped or masked to.
 *
 * The two opacities are next to each other on purpose. They are the pair Photoshop users mix up most
 * often, and the fastest way to teach the difference is to put them one above the other where
 * dragging one and then the other shows it: layer opacity fades the stroke and the shadow with the
 * letterform, fill opacity takes the letterform away and leaves them standing.
 */
@Composable
fun LayerParametersSheetBody(
    state: EditorState,
    content: SheetContent.LayerParameters,
    model: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val layer = state.document.findLayer(content.layer)
    if (layer == null) {
        SheetHint("این لایه دیگر وجود ندارد")
        return
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetHint(layer.name)

        SheetSection("شفافیت")
        SheetSlider(
            label = "اوپاسیتی لایه",
            value = layer.opacity * PERCENT,
            range = 0f..PERCENT,
            onChange = { value, continuous ->
                model.act { setLayerOpacity(layer.id, value / PERCENT, continuous) }
            },
            onCommit = { model.act { endScrub() } },
        )
        SheetSlider(
            label = "اوپاسیتی فیل",
            value = layer.style.fillOpacity * PERCENT,
            range = 0f..PERCENT,
            onChange = { value, continuous ->
                model.act { setFillOpacity(layer.id, value / PERCENT, continuous) }
            },
            onCommit = { model.act { endScrub() } },
        )
        SheetHint("فیل فقط رنگ خود لایه را کم می‌کند و افکت‌ها را دست‌نخورده می‌گذارد")

        SheetSection("حالت ترکیب")
        for (group in BLEND_GROUPS) {
            SheetChips {
                for (mode in group) {
                    SheetChip(mode.persianLabel, chosen = layer.blendMode == mode) {
                        model.act { setLayerBlendMode(layer.id, mode) }
                    }
                }
            }
        }

        SheetSection("ماسک و برش")
        SheetChips {
            SheetChip(
                if (layer.clipped) "برش‌خورده به لایهٔ زیر" else "برش به لایهٔ زیر",
                chosen = layer.clipped,
            ) {
                model.act { setClipped(layer.id, !layer.clipped) }
            }
            SheetChip("ماسک از انتخاب", enabled = model.select.selection != null) {
                model.maskFromSelection()
            }
            SheetChip("حذف ماسک", enabled = layer.mask != null) { model.removeMask() }
        }
        if (layer is Layer.Group) {
            SheetChips {
                SheetChip(
                    if (layer.passThrough) "عبوردهنده" else "مستقل",
                    chosen = !layer.passThrough,
                ) {
                    model.act { setGroupPassThrough(layer.id, !layer.passThrough) }
                }
            }
            SheetHint("گروه مستقل، فرزندانش را اول روی هم می‌گذارد و بعد با پایین ترکیب می‌کند")
        }

        if (layer is Layer.Text) {
            SheetSection("تبدیل")
            SheetAction("متن به شکل", enabled = model.canConvertToShape()) { model.convertTextToShape() }
            // Said before it happens, not after: a user who loses an editable headline without
            // warning does not forgive the tool a second time.
            SheetHint("پس از تبدیل، دیگر نمی‌توانید متن را ویرایش کنید — گره‌ها جای حروف را می‌گیرند")
        }
    }
}

/**
 * The blend modes, in Photoshop's own six groups.
 *
 * The grouping is not decoration: the modes within a group behave alike — the darkening family, the
 * lightening family, the contrast family — so a user hunting for "something like Multiply but
 * gentler" looks in one place. A flat list of twenty-seven names is a list nobody reads past the
 * fifth entry.
 */
private val BLEND_GROUPS: List<List<BlendMode>> = listOf(
    listOf(BlendMode.NORMAL, BlendMode.DISSOLVE),
    listOf(
        BlendMode.DARKEN, BlendMode.MULTIPLY, BlendMode.COLOR_BURN,
        BlendMode.LINEAR_BURN, BlendMode.DARKER_COLOR,
    ),
    listOf(
        BlendMode.LIGHTEN, BlendMode.SCREEN, BlendMode.COLOR_DODGE,
        BlendMode.LINEAR_DODGE, BlendMode.LIGHTER_COLOR,
    ),
    listOf(
        BlendMode.OVERLAY, BlendMode.SOFT_LIGHT, BlendMode.HARD_LIGHT, BlendMode.VIVID_LIGHT,
        BlendMode.LINEAR_LIGHT, BlendMode.PIN_LIGHT, BlendMode.HARD_MIX,
    ),
    listOf(BlendMode.DIFFERENCE, BlendMode.EXCLUSION, BlendMode.SUBTRACT, BlendMode.DIVIDE),
    listOf(BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY),
)

/** Opacity is shown as a percentage, which is the number the user knows it by. */
private const val PERCENT = 100f
