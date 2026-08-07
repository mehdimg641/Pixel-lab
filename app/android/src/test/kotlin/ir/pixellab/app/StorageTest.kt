package ir.pixellab.app

import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import ir.pixellab.core.codec.Project
import ir.pixellab.core.codec.ProjectFile
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the user's work goes.
 *
 * The interesting cases are all about names and interruption, not about the format — that is tested
 * in `core:codec`. A save that leaves a truncated file behind is the failure that costs real work,
 * because the truncated file is the one that gets opened next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun document(name: String) = Document(
        id = DocumentId("d"),
        canvas = CanvasSpec(1080, 1350),
        name = name,
        layers = listOf(
            Layer.Shape(LayerId("s"), ShapeGeometry.Rectangle(Vec2(400f, 200f)), name = "مستطیل"),
        ),
    )

    @Test
    fun `a saved project reads back`() {
        val file = Storage.saveProject(context, Project(document("کاور من")), "کاور من")
        val back = Storage.loadProject(file)
        back.document.name shouldBe "کاور من"
        back.document.layers.single().name shouldBe "مستطیل"
    }

    @Test
    fun `a persian name survives onto the file system`() {
        val file = Storage.saveProject(context, Project(document("پروژه")), "پروژه")
        // The whole interface is Persian, and a store that transliterates or mangles names gives
        // the user a folder of files they cannot tell apart.
        file.name shouldBe "پروژه.${ProjectFile.EXTENSION}"
        file.exists() shouldBe true
    }

    @Test
    fun `a name with a path separator cannot escape the projects folder`() {
        val file = Storage.saveProject(context, Project(document("../../etc/passwd")), "../../etc/passwd")
        file.parentFile!!.canonicalPath shouldBe Storage.projectsDirectory(context).canonicalPath
        ("/" in file.nameWithoutExtension) shouldBe false
    }

    @Test
    fun `saving over an existing project replaces it and leaves no temporary behind`() {
        Storage.saveProject(context, Project(document("v1")), "same")
        Storage.saveProject(context, Project(document("v2")), "same")

        val files = Storage.projectsDirectory(context).listFiles()!!.map { it.name }
        // The temporary is the whole point of the atomic write, so it must not survive it.
        files.none { it.endsWith(".tmp") } shouldBe true
        Storage.loadProject(File(Storage.projectsDirectory(context), "same.pxl")).document.name shouldBe "v2"
    }

    @Test
    fun `listing gives the most recently touched project first`() {
        Storage.saveProject(context, Project(document("older")), "older")
        val newer = Storage.saveProject(context, Project(document("newer")), "newer")
        // Robolectric's clock does not advance between two writes in the same millisecond, so the
        // ordering is asserted against an explicitly stamped file rather than against the timing.
        newer.setLastModified(System.currentTimeMillis() + 10_000)

        Storage.listProjects(context).first().name shouldBe "newer.pxl"
    }

    @Test
    fun `listing ignores anything that is not a project`() {
        Storage.saveProject(context, Project(document("real")), "real")
        File(Storage.projectsDirectory(context), "notes.txt").writeText("hello")
        File(Storage.projectsDirectory(context), "half-written.pxl.tmp").writeText("junk")

        Storage.listProjects(context).map { it.name } shouldBe listOf("real.pxl")
    }

    @Test
    fun `an empty name still produces a file`() {
        // Reachable: a new document that the user renamed to nothing, then saved.
        Storage.sanitise("") shouldBe "untitled"
        Storage.saveProject(context, Project(document("")), "").exists() shouldBe true
    }

    @Test
    fun `a very long name is trimmed to something the file system accepts`() {
        val long = "ب".repeat(500)
        // Most Android file systems cap a name at 255 bytes, and Persian is two bytes a character,
        // so an untrimmed name fails at the write rather than at the rename.
        (Storage.sanitise(long).length <= 120) shouldBe true
        Storage.saveProject(context, Project(document(long)), long).exists() shouldBe true
    }

    @Test
    fun `assets and fonts stored with a project come back with it`() {
        val project = Project(
            document = document("bundled"),
            assets = mapOf("photo" to byteArrayOf(1, 2, 3)),
            fonts = mapOf("Dana.ttf" to byteArrayOf(4, 5)),
        )
        val back = Storage.loadProject(Storage.saveProject(context, project, "bundled"))
        back.assets.getValue("photo").toList() shouldBe listOf<Byte>(1, 2, 3)
        back.fonts.getValue("Dana.ttf").toList() shouldBe listOf<Byte>(4, 5)
    }
}
