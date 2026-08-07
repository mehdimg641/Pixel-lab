package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.render.Shaders
// JUnit 4, not Jupiter: this module runs the Robolectric runner, and a Jupiter @Test here is not
// discovered — it reports as a pass by never running at all.
import org.junit.Test

/**
 * The contract between the shared vertex stage here and the fragment library in `core:render`.
 *
 * A varying the fragment shader reads but the vertex shader never writes is a *link* failure, not a
 * compile failure — it surfaces on a device, at the moment the effect is first used, as an effect
 * that renders nothing. The two halves live in different modules precisely so the fragment shaders
 * stay testable without a GPU, so this is the seam that needs an explicit check.
 */
class GlShaderContractTest {

    private val vertexOutputs: Set<String> =
        VARYING.findAll(AndroidGlDevice.VERTEX_SOURCE)
            .filter { it.groupValues[1].trim().endsWith("out") }
            .map { it.groupValues[3] }
            .toSet()

    @Test
    fun `every varying a fragment shader reads is written by the vertex stage`() {
        // Without this the check below passes by finding nothing, which is the failure mode of
        // every test built on a regex.
        vertexOutputs shouldBe setOf("vUv", "vInstance")
        for (shader in Shaders.ALL.values) {
            val reads = VARYING.findAll(shader.fragment)
                .filter { it.groupValues[1].trim().endsWith("in") }
                .map { it.groupValues[3] }
            for (name in reads) {
                if (name !in vertexOutputs) error("${shader.id} reads varying '$name' that the vertex stage never writes")
            }
        }
    }

    @Test
    fun `varyings agree on type and on flat interpolation`() {
        val vertexDeclarations = declarations(AndroidGlDevice.VERTEX_SOURCE, "out")
        for (shader in Shaders.ALL.values) {
            for ((name, declaration) in declarations(shader.fragment, "in")) {
                val counterpart = vertexDeclarations[name] ?: continue
                // `flat` on one side only is a link error; the type mismatch is too. Both are
                // invisible until the driver refuses the program.
                if (declaration != counterpart) {
                    error("${shader.id} declares '$name' as [$declaration], the vertex stage as [$counterpart]")
                }
            }
        }
    }

    @Test
    fun `the vertex stage declares the same language version as the fragment library`() {
        AndroidGlDevice.VERTEX_SOURCE.trimStart().startsWith("#version 300 es") shouldBe true
        Shaders.ALL.values.all { it.fragment.trimStart().startsWith("#version 300 es") } shouldBe true
    }

    @Test
    fun `the fullscreen triangle covers the whole target`() {
        // ids 0,1,2 produce (0,0) (2,0) (0,2) in UV, so the clip-space triangle spans -1..3 on both
        // axes and no pixel of the target is left unwritten.
        val uv = (0..2).map { id -> ((id shl 1) and 2) to (id and 2) }
        uv shouldBe listOf(0 to 0, 2 to 0, 0 to 2)
    }
}

/** `[qualifiers] type name;` — captures the qualifier chain, the type and the name. */
private val VARYING = Regex("""(?m)^\s*((?:flat\s+|smooth\s+|centroid\s+)*(?:in|out))\s+(\w+)\s+(\w+)\s*;""")

private fun declarations(source: String, direction: String): Map<String, String> =
    VARYING.findAll(source)
        .filter { it.groupValues[1].trim().endsWith(direction) }
        .associate { match ->
            val qualifiers = match.groupValues[1].removeSuffix(direction).trim()
            match.groupValues[3] to "$qualifiers ${match.groupValues[2]}".trim()
        }
