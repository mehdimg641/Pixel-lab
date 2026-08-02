package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Light
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Vec3
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
    val layer = state.primaryLayer as? Layer.Text
    if (layer == null) {
        Column(modifier.fillMaxWidth()) {
            SheetHint("یک لایهٔ متن انتخاب کنید — سه‌بعدی از خود حروف ساخته می‌شود")
        }
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
private fun MaterialControls(label: String, material: Material, onChange: (Material) -> Unit) {
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
private const val MIN_FOV = 10f
private const val MAX_FOV = 90f
private const val MAX_INTENSITY = 6f
private const val HALF_TURN = 180f
private const val QUARTER_TURN = 90f
private const val HALF = 0.5f

/** Four samples an axis: sixteen per pixel, which is where the bevel's edge stops shimmering. */
private const val EXPORT_SAMPLES = 4
