package ir.pixellab.core.codec

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.encode
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProjectFileTest {

    private fun document() = Document(
        id = DocumentId("d1"),
        canvas = CanvasSpec(1080, 1350),
        name = "کاور نمونه",
        layers = listOf(
            Layer.Shape(
                id = LayerId("shape"),
                geometry = ShapeGeometry.Rectangle(Vec2(400f, 200f)),
                name = "مستطیل",
                transform = Transform(translation = Vec2(30f, 40f), rotation = 12f),
                style = Style(
                    fill = Fill.Solid(Color(0.2f, 0.4f, 0.9f)),
                    effects = listOf(
                        Effect.DropShadow(blur = 18f, distance = 7f),
                        Effect.Stroke(6f, Fill.Solid(Color.WHITE)),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a project survives a round trip`() {
        val original = Project(document())
        val back = ProjectFile.decode(ProjectFile.encode(original))

        back.document.name shouldBe "کاور نمونه"
        back.document.canvas.width shouldBe 1080
        back.document.layers.size shouldBe 1
    }

    @Test
    fun `a layer's transform and effects come back exactly`() {
        val back = ProjectFile.decode(ProjectFile.encode(Project(document())))
        val layer = back.document.findLayer(LayerId("shape"))!!
        // A project that loses the effect stack is a project that lost the work.
        layer.transform.rotation shouldBe 12f
        layer.transform.translation shouldBe Vec2(30f, 40f)
        layer.style.effects.size shouldBe 2
        (layer.style.effects[0] as Effect.DropShadow).blur shouldBe 18f
    }

    @Test
    fun `persian names survive the encoding`() {
        val back = ProjectFile.decode(ProjectFile.encode(Project(document())))
        // The whole interface is Persian; a project format that mangles layer names is unusable
        // however well it preserves geometry.
        back.document.findLayer(LayerId("shape"))!!.name shouldBe "مستطیل"
    }

    @Test
    fun `assets and fonts travel with the document`() {
        val project = Project(
            document = document(),
            assets = mapOf("photo-1" to byteArrayOf(1, 2, 3, 4)),
            fonts = mapOf("Vazir.ttf" to byteArrayOf(9, 8, 7)),
        )
        val back = ProjectFile.decode(ProjectFile.encode(project))
        // Storing only the document gives a file that opens on the device it was made on and
        // nowhere else — and loses a layer the moment the user tidies their photos.
        back.assets.getValue("photo-1").toList() shouldBe listOf<Byte>(1, 2, 3, 4)
        back.fonts.getValue("Vazir.ttf").toList() shouldBe listOf<Byte>(9, 8, 7)
    }

    @Test
    fun `an entry from a future version is skipped rather than failing the load`() {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ProjectFile.DOCUMENT_ENTRY))
                zip.write(document().encode().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("timeline/keyframes.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        // A later version that adds a directory must still open here, or every user is stranded on
        // whichever build wrote their files.
        ProjectFile.decode(bytes).document.layers.size shouldBe 1
    }

    @Test
    fun `an archive with no document is refused clearly`() {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("not a project".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows<CodecException> { ProjectFile.decode(bytes) }
    }

    @Test
    fun `a project is told apart from any other zip`() {
        ProjectFile.looksLikeProject(ProjectFile.encode(Project(document()))) shouldBe true

        val plainZip = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("photo.jpg"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()
        // A zip and a project share a magic number, so the document entry is the only real test.
        ProjectFile.looksLikeProject(plainZip) shouldBe false
        ProjectFile.looksLikeProject("not a zip".toByteArray()) shouldBe false
    }

    @Test
    fun `the document is the first entry so a truncated file still yields structure`() {
        val bytes = ProjectFile.encode(
            Project(document(), assets = mapOf("big" to ByteArray(50_000))),
        )
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        names.first() shouldBe ProjectFile.DOCUMENT_ENTRY
    }
}
