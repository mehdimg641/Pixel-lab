package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.render.ParameterSpec
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import ir.pixellab.core.model.Layer
import kotlin.math.roundToInt

/**
 * The brush, with the artwork it is going to touch.
 *
 * ### Why the brush deserves a room
 *
 * `BrushSheetBody` has around forty controls — tip, hardness, dynamics, scattering, texture,
 * smoothing, the tone protection — and every one of them is worth having. But four of them are the
 * ones a hand changes between strokes, and reaching those four through a sheet that covers the
 * picture is the difference between a brush somebody tunes and a brush somebody accepts the
 * defaults of.
 *
 * So this screen is deliberately *small*: the preset, the size, the flow, the colour. Everything
 * else is one chip away, in the panel that already holds it, unchanged.
 *
 * ### The dot in the corner
 *
 * It is drawn at the brush's real diameter, at the brush's real flow, in the brush's real colour.
 * That is three numbers you would otherwise learn by making a mark and undoing it. It is the one
 * piece of this screen that is not a control, and it is the reason the sliders are worth having
 * here rather than in a panel: you can see what they mean without spending a stroke.
 *
 * Clamped in size, because a 400px brush at 1:1 would be most of the canvas and the preview would
 * stop being a preview and start being an obstruction.
 */
@Composable
fun BrushStudioScreen(model: EditorViewModel, onBack: () -> Unit, onPickImage: () -> Unit) {
    val state = model.state
    val preset = model.paint.preset
    val foreground = model.palette[model.editingSlot]

    Column(Modifier.fillMaxSize().background(Ink.Ground).systemBarsPadding()) {
        StudioHeader("استودیو قلم", onBack = onBack) {
            Text(
                "${Digits.technical(state.document.canvas.width)} × " +
                    "${Digits.technical(state.document.canvas.height)} · " +
                    "${Digits.technical(state.document.layers.size)} لایه",
                style = MeasureStyle,
                color = Ink.TextMuted,
                maxLines = 1,
                modifier = Modifier.padding(end = Space.small),
            )
        }

        StudioCanvas(model, Modifier.weight(1f).fillMaxWidth()) {
            // The swatches down the leading edge, which is where the brief puts them and where a
            // hand already is. Vertical rather than the panel's wrapping row because this column
            // has to sit beside the artwork without covering a band of it.
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(Space.small)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.tight),
            ) {
                for ((name, swatch) in BASIC_SWATCHES) {
                    val here = swatch.r == foreground.r &&
                        swatch.g == foreground.g &&
                        swatch.b == foreground.b
                    Box(
                        Modifier
                            .size(Space.touch)
                            .clickable(onClickLabel = name) { model.setPaletteColor(swatch) }
                            .semantics {
                                role = Role.RadioButton
                                selected = here
                                contentDescription = name
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(SWATCH)
                                .clip(CircleShape)
                                .background(
                                    androidx.compose.ui.graphics.Color(swatch.r, swatch.g, swatch.b, 1f),
                                )
                                .border(
                                    width = if (here) 2.5.dp else 1.dp,
                                    color = if (here) Ink.Accent else Ink.Divider,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Space.large)
                    .size(preset.size.coerceIn(DOT_MIN, DOT_MAX).dp)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Color(
                            foreground.r,
                            foreground.g,
                            foreground.b,
                            preset.flow.coerceIn(0.05f, 1f),
                        ),
                    )
                    // An edge, or a black brush at low flow over the dark surround is a preview
                    // that reads as "nothing is drawn here" — which is the one thing it must never
                    // say, since that is also what a broken preview looks like.
                    .border(1.dp, Ink.Divider, CircleShape),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Ink.Chrome)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Space.large),
        ) {
            Box(Modifier.fillMaxWidth().width(1.dp).background(Ink.Divider))

            // A paint layer or nothing to paint on. Said here rather than discovered by drawing a
            // stroke that lands nowhere — the rule this application holds itself to is that a panel
            // naming a precondition offers the way to meet it.
            if (state.primaryLayer !is Layer.Image) {
                MissingSubject(
                    message = "لایه‌ای برای نقاشی انتخاب نشده",
                    action = "لایهٔ تازه",
                ) { model.addPaintLayer() }
            }

            PresetRow(preset) { model.paint.preset = it }

            BrushSlider("اندازه", preset.size, 1f..400f, ParameterSpec.Slider.Unit.PIXELS) {
                model.paint.preset = preset.copy(size = it)
            }
            BrushSlider("جریان", preset.flow, 0.01f..1f, ParameterSpec.Slider.Unit.PERCENT) {
                model.paint.preset = preset.copy(flow = it.coerceIn(0.01f, 1f))
            }

            SheetChips {
                // The other thirty-odd controls, one press away and in the panel that has always
                // held them. A studio that duplicated them would be a second place for the same
                // settings to disagree with the first.
                SheetChip("همهٔ تنظیمات") {
                    model.act { openSheet(SheetContent.BrushSettings, SheetDetent.FULL) }
                }
                SheetChip("افزودن عکس", onClick = onPickImage)
            }
            SheetHint(
                "قلم ${Digits.technical(preset.size.roundToInt())} پیکسل · " +
                    "جریان ${Digits.technical((preset.flow * 100f).roundToInt())}٪",
            )
        }
    }
}

/** The circle a swatch draws, inside a full-size target. Same as the panel's, and for the reason. */
private val SWATCH = 28.dp

/**
 * What the preview dot is allowed to be.
 *
 * A 1px brush drawn at 1px is invisible and reads as "the preview is broken"; a 400px one at 1:1 is
 * most of a phone canvas. Both ends are clamped, and between them the dot is the brush's true size
 * — which is the range where the number actually means something to a hand.
 */
private const val DOT_MIN = 6f
private const val DOT_MAX = 96f
