package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.mesh.MarkStyle
import ir.pixellab.core.model.ShadowCast
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Light
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Real three-dimensional text.
 *
 * The panel this whole application was built for, so it is worth being clear about what it is not.
 * The layer-effect route — a stack of offset copies — cannot do the two things the eye actually
 * reads depth from: a bevel whose highlight *moves* as the letter turns, and side walls that fall
 * off towards the back. In a stack every copy is lit the same, so it reads as a thick sticker.
 *
 * The three material slots are not decoration. The reference covers paint gold on the bevel and the
 * side walls while keeping the face white, and one material cannot say that — it is exactly what
 * makes an extruded letter read as *carved* rather than as a coloured slab.
 */
@Composable
fun DimensionalSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Scanned here rather than held by the view model: the whole point of the assets folder is that
    // the user copies a file in from *outside* the app, so there is no event to react to and the
    // only correct time to look is when the panel that offers them is being drawn.
    val environments = remember {
        AssetLibrary.scan(context).firstOrNull { it.kind == AssetKind.ENVIRONMENTS }?.files.orEmpty()
    }
    val layer = state.primaryLayer as? Layer.Text
    if (layer == null) {
        // The panel this application exists for, dead-ending on a precondition nothing in the
        // interface could meet.
        MissingSubject(
            message = "یک لایهٔ متن انتخاب کنید — سه‌بعدی از خود حروف ساخته می‌شود",
            action = "افزودن متن",
            onAct = LocalEditorActions.current.addText,
            modifier = modifier,
        )
        return
    }

    val id = layer.id
    val geometry = model.geometry3DOf(id)
    fun put(next: Geometry3D) = model.setGeometry3D(id, next)

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {

        SheetSection("پیش‌تنظیم")
        SheetChips {
            for (preset in PRESETS) {
                SheetChip(preset.label) { put(preset.build(geometry)) }
            }
        }

        SheetSection("برجستگی")
        SheetSlider("عمق", geometry.depth, 0f..MAX_DEPTH, onChange = { value, _ ->
            put(geometry.copy(depth = value))
        })
        SheetSlider("اندازهٔ پخ", geometry.bevelSize, 0f..MAX_BEVEL, onChange = { value, _ ->
            put(geometry.copy(bevelSize = value))
        })
        // The one number people get wrong, and it does not fail loudly — the letter just thickens.
        SheetHint("پخ بزرگ‌تر از باریک‌ترین قلمِ حرف، آن قلم را به یک تیغه بدون سطح تبدیل می‌کند")

        SheetSection("نقطه‌ها و اعراب")
        // Why this section exists at all, in one line the user can act on.
        SheetHint("نقطهٔ ب پ ت ث ج خ ز ض ظ غ ف ق ن و اعراب، جدا از تنهٔ حرف کنترل می‌شوند")
        SheetChips {
            for (style in MARK_STYLES) {
                SheetChip(
                    style.label,
                    chosen = geometry.markDepth == style.depth && geometry.markLift == style.lift,
                ) {
                    put(geometry.copy(markDepth = style.depth, markLift = style.lift))
                }
            }
        }
        // Both are multiples of the letter's own depth, so moving the depth slider afterwards keeps
        // whatever look was set here instead of undoing it.
        SheetSlider("عمق نقطه", geometry.markDepth, 0f..MAX_MARK_DEPTH, onChange = { value, _ ->
            put(geometry.copy(markDepth = value))
        })
        SheetSlider("جلوآمدگی نقطه", geometry.markLift, 0f..MAX_MARK_LIFT, onChange = { value, _ ->
            put(geometry.copy(markLift = value))
        })
        SheetChips {
            SheetChip("متریال جدا", chosen = geometry.markMaterial != null) {
                put(
                    geometry.copy(
                        // Starting from the face rather than from a default, so the first thing the
                        // user sees after switching this on is the letter they already had.
                        markMaterial = if (geometry.markMaterial == null) geometry.faceMaterial else null,
                    ),
                )
            }
        }
        geometry.markMaterial?.let { mark ->
            MaterialControls("نقطه", mark) { put(geometry.copy(markMaterial = it)) }
        }

        SheetSection("چرخش")
        SheetSlider("افقی", geometry.rotation.y, -MAX_TURN..MAX_TURN, onChange = { value, _ ->
            put(geometry.copy(rotation = geometry.rotation.copy(y = value)))
        })
        SheetSlider("عمودی", geometry.rotation.x, -MAX_TURN..MAX_TURN, onChange = { value, _ ->
            put(geometry.copy(rotation = geometry.rotation.copy(x = value)))
        })
        SheetSlider("پیچش", geometry.rotation.z, -MAX_TURN..MAX_TURN, onChange = { value, _ ->
            put(geometry.copy(rotation = geometry.rotation.copy(z = value)))
        })
        SheetSlider("میدان دید", geometry.fieldOfView, MIN_FOV..MAX_FOV, onChange = { value, _ ->
            put(geometry.copy(fieldOfView = value))
        })
        SheetHint("میدان دید کمتر، پرسپکتیو را صاف‌تر می‌کند — بیشتر، حروف را اغراق‌آمیز عمیق")

        // ---- the cast shadow ------------------------------------------------------------------

        SheetSection("سایه")
        val shadow = geometry.shadow
        SheetChips {
            SheetChip("سایه‌اندازی", chosen = shadow != null) {
                put(geometry.copy(shadow = if (shadow == null) ShadowCast() else null))
            }
        }
        if (shadow == null) {
            SheetHint("بدون سایه، حروف روی زمینه شناورند — سایه چیزی است که می‌گوید کجا ایستاده‌اند")
        } else {
            SheetSlider("فاصله", shadow.distance, 0f..MAX_SHADOW_THROW, onChange = { value, _ ->
                put(geometry.copy(shadow = shadow.copy(distance = value)))
            })
            SheetSlider("نرمی", shadow.softness, 0f..MAX_SHADOW_SOFTNESS, onChange = { value, _ ->
                put(geometry.copy(shadow = shadow.copy(softness = value)))
            })
            SheetSlider("تیرگی", shadow.opacity, 0f..1f, onChange = { value, _ ->
                put(geometry.copy(shadow = shadow.copy(opacity = value)))
            })
            // Screen angle, not a direction vector. Nobody sets a shadow by typing three
            // components, and Photoshop has asked for an angle here since layer styles existed.
            SheetSlider("زاویه", shadowAngle(shadow), 0f..FULL_TURN, onChange = { value, _ ->
                put(geometry.copy(shadow = shadow.copy(direction = shadowDirection(value, shadow))))
            })
            SheetHint(
                "فاصله کسری از بلندی حروف است. کمتر از آنچه هندسه اجازه می‌دهد نمی‌شود — " +
                    "برجستگی عمیق‌تر، کوتاه‌ترین سایهٔ ممکنش بلندتر است",
            )
        }

        SheetSection("متریال")
        MaterialControls("رو", geometry.faceMaterial) { put(geometry.copy(faceMaterial = it)) }
        MaterialControls("پخ", geometry.bevelMaterial) { put(geometry.copy(bevelMaterial = it)) }
        MaterialControls("کناره", geometry.sideMaterial) { put(geometry.copy(sideMaterial = it)) }

        SheetSection("نور")
        LightControls("نور اصلی", geometry.lighting.key) {
            put(geometry.copy(lighting = geometry.lighting.copy(key = it)))
        }
        LightControls("نور پرکننده", geometry.lighting.fill) {
            put(geometry.copy(lighting = geometry.lighting.copy(fill = it)))
        }
        SheetChips {
            SheetChip("نور لبه", chosen = geometry.lighting.rim != null) {
                put(
                    geometry.copy(
                        lighting = geometry.lighting.copy(
                            // From behind and to the side: a rim light exists to separate the
                            // letter from whatever is behind it, and it can only do that from
                            // an angle the key light does not already reach.
                            rim = if (geometry.lighting.rim == null) {
                                Light(direction = Vec3(0.3f, 0.4f, 0.9f), intensity = 1.4f, castsShadow = false)
                            } else {
                                null
                            },
                        ),
                    ),
                )
            }
        }

        SheetSection("محیط بازتاب")
        // The whole reason a metal looks like metal. Three point lights cannot light a mirror: a
        // chrome letter shaded by direct light alone is a flat grey slab, because what a mirror
        // shows is the *room*. The generated studio is what makes that work with no assets at all;
        // this is for when the user has a real photograph of a real place.
        SheetChips {
            SheetChip("استودیوی تولیدشده", chosen = geometry.lighting.environment == null) {
                model.setEnvironment(id, null, "")
            }
            for (file in environments) {
                val name = file.name.substringBeforeLast('.')
                SheetChip(name, chosen = geometry.lighting.environment?.value == "hdr:$name") {
                    scope.launch {
                        val image = withContext(Dispatchers.IO) {
                            runCatching { ir.pixellab.core.codec.Codecs.decode(file.readBytes()) }.getOrNull()
                        }
                        model.setEnvironment(id, image, name)
                    }
                }
            }
        }
        if (geometry.lighting.environment != null) {
            SheetSlider("شدت محیط", geometry.lighting.environmentIntensity, 0f..4f, onChange = { v, _ ->
                put(geometry.copy(lighting = geometry.lighting.copy(environmentIntensity = v)))
            })
            // Turning the world rather than the letter, which is how a highlight is placed on a
            // curve without moving the letter off the layout.
            SheetSlider("چرخش محیط", geometry.lighting.environmentRotation, 0f..360f, onChange = { v, _ ->
                put(geometry.copy(lighting = geometry.lighting.copy(environmentRotation = v)))
            })
        } else {
            SheetHint("پوشهٔ hdr در فضای اپ را پر کنید تا اینجا فهرست شود — تصویر ۳۶۰ درجه (equirectangular)")
        }

        SheetSection("ساخت")
        SheetAction("ساخت سه‌بعدی") {
            scope.launch { model.render3D(id) }
        }
        SheetAction("ساخت با کیفیت خروجی") {
            scope.launch { model.render3D(id, supersample = EXPORT_SAMPLES) }
        }
        // Why there is a build button at all, rather than a live preview.
        SheetHint("متن اصلی می‌ماند و فقط پنهان می‌شود — پس اگر کلمه عوض شد، دوباره می‌سازید")
    }
}

@Composable
internal fun MaterialControls(label: String, material: Material, onChange: (Material) -> Unit) {
    SheetHint(label)
    SheetChips {
        for (preset in MATERIALS) {
            SheetChip(preset.first) { onChange(preset.second) }
        }
    }
    // Metal is a switch, not a slider, because there is nothing physically in between: a surface is
    // a conductor or it is not, and the values between the two describe no real material.
    SheetChips {
        SheetChip("فلز", chosen = material.metallic > HALF) {
            onChange(material.copy(metallic = if (material.metallic > HALF) 0f else 1f))
        }
    }
    SheetSlider("زبری $label", material.roughness, 0f..1f, onChange = { value, _ ->
        onChange(material.copy(roughness = value))
    })
    SheetSlider("لاک $label", material.clearCoat, 0f..1f, onChange = { value, _ ->
        onChange(material.copy(clearCoat = value))
    })
    ColorPickerBody(color = material.baseColor, onChange = { onChange(material.copy(baseColor = it)) })
}

@Composable
private fun LightControls(label: String, light: Light, onChange: (Light) -> Unit) {
    SheetHint(label)
    SheetSlider("شدت $label", light.intensity, 0f..MAX_INTENSITY, onChange = { value, _ ->
        onChange(light.copy(intensity = value))
    })
    // Two angles rather than three numbers: a direction is a point on a sphere, and asking for its
    // x, y and z is asking the user to normalise a vector in their head.
    SheetSlider("زاویهٔ افقی $label", azimuth(light.direction), -HALF_TURN..HALF_TURN, onChange = { value, _ ->
        onChange(light.copy(direction = direction(value, elevation(light.direction))))
    })
    SheetSlider("ارتفاع $label", elevation(light.direction), -QUARTER_TURN..QUARTER_TURN, onChange = { value, _ ->
        onChange(light.copy(direction = direction(azimuth(light.direction), value)))
    })
}

private fun azimuth(d: Vec3): Float =
    Math.toDegrees(kotlin.math.atan2(d.x.toDouble(), -d.z.toDouble())).toFloat()

private fun elevation(d: Vec3): Float {
    val horizontal = kotlin.math.sqrt(d.x * d.x + d.z * d.z)
    return Math.toDegrees(kotlin.math.atan2(-d.y.toDouble(), horizontal.toDouble())).toFloat()
}

private fun direction(azimuthDegrees: Float, elevationDegrees: Float): Vec3 {
    val a = Math.toRadians(azimuthDegrees.toDouble())
    val e = Math.toRadians(elevationDegrees.toDouble())
    val horizontal = kotlin.math.cos(e)
    return Vec3(
        x = (kotlin.math.sin(a) * horizontal).toFloat(),
        y = (-kotlin.math.sin(e)).toFloat(),
        z = (-kotlin.math.cos(a) * horizontal).toFloat(),
    )
}

private val MATERIALS = listOf(
    "طلا" to Material.GOLD,
    "کروم" to Material.CHROME,
    "براق" to Material.GLOSSY_WHITE,
    "مات" to Material.MATTE,
)

/**
 * The three treatments of a letter's dots, named.
 *
 * Not the same list as the material presets: these do not touch colour at all, they only say how far
 * the dot follows the body. The numbers come from the geometry module rather than being retyped here
 * — a preset the panel and the extruder disagreed about would show a chip as unselected the instant
 * after the user picked it.
 */
private class MarkStylePreset(val label: String, style: MarkStyle) {
    val depth = style.depth
    val lift = style.lift
}

private val MARK_STYLES = listOf(
    MarkStylePreset("همسطح", MarkStyle.FLUSH),
    MarkStylePreset("فرورفته", MarkStyle.INSET),
    MarkStylePreset("شناور", MarkStyle.FLOATING),
)

/** A named starting point, applied over whatever depth and rotation the user already set. */
private class Preset(val label: String, val build: (Geometry3D) -> Geometry3D)

private val PRESETS = listOf(
    // The cover treatment this app exists for: a white face with gold edges, which is what makes
    // the letter read as carved rather than as a coloured slab.
    Preset("جلد طلایی") { g ->
        g.copy(
            faceMaterial = Material.GLOSSY_WHITE,
            bevelMaterial = Material.GOLD,
            sideMaterial = Material.GOLD,
        )
    },
    Preset("کروم") { g ->
        g.copy(
            faceMaterial = Material.CHROME,
            bevelMaterial = Material.CHROME,
            sideMaterial = Material.CHROME,
        )
    },
    Preset("پلاستیک آب‌نباتی") { g ->
        val candy = Material(
            baseColor = ir.pixellab.core.model.Color(0.90f, 0.20f, 0.35f),
            roughness = 0.35f,
            clearCoat = 1f,
        )
        g.copy(faceMaterial = candy, bevelMaterial = candy, sideMaterial = candy)
    },
    Preset("سنگ مات") { g ->
        val stone = Material(baseColor = ir.pixellab.core.model.Color(0.62f, 0.60f, 0.57f), roughness = 0.95f)
        g.copy(faceMaterial = stone, bevelMaterial = stone, sideMaterial = stone)
    },
)

private const val MAX_DEPTH = 400f
private const val MAX_BEVEL = 60f
private const val MAX_TURN = 60f

/** Twice the body's depth, which is past every sane value and short of a dot that reads as a slab. */
private const val MAX_MARK_DEPTH = 2f

/** Far enough that the dot clears the body entirely and casts its own shadow. */
private const val MAX_MARK_LIFT = 2f

/**
 * The shadow's direction as an angle on screen, and back again.
 *
 * The model holds a direction vector because that is what the projection needs; a person setting a
 * shadow is pointing at a clock face. The z component — how frontal the source is — is what decides
 * the *shortest* shadow the geometry can produce, so it is preserved across a turn of the dial
 * rather than recomputed: turning the angle should sweep the shadow round, not change its length.
 */
private fun shadowAngle(shadow: ShadowCast): Float {
    val degrees = Math.toDegrees(kotlin.math.atan2(-shadow.direction.y, shadow.direction.x).toDouble())
    return ((degrees + FULL_TURN) % FULL_TURN).toFloat()
}

private fun shadowDirection(angle: Float, shadow: ShadowCast): Vec3 {
    val lateral = kotlin.math.hypot(shadow.direction.x, shadow.direction.y)
    val radians = Math.toRadians(angle.toDouble())
    return Vec3(
        (kotlin.math.cos(radians) * lateral).toFloat(),
        (-kotlin.math.sin(radians) * lateral).toFloat(),
        shadow.direction.z,
    )
}

private const val FULL_TURN = 360f

/** Past a third of the letters' height the type stops reading as printed and starts to float. */
private const val MAX_SHADOW_THROW = 0.6f

/** A fraction of the frame; beyond this the shadow is a wash rather than a shadow. */
private const val MAX_SHADOW_SOFTNESS = 0.4f

private const val MIN_FOV = 10f
private const val MAX_FOV = 90f
private const val MAX_INTENSITY = 6f
private const val HALF_TURN = 180f
private const val QUARTER_TURN = 90f
private const val HALF = 0.5f

/** Four samples an axis: sixteen per pixel, which is where the bevel's edge stops shimmering. */
private const val EXPORT_SAMPLES = 4
