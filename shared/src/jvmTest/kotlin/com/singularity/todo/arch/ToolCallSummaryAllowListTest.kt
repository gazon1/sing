package com.singularity.todo.arch

import com.singularity.todo.core.llm.FREE_TEXT_FIELDS
import com.singularity.todo.core.llm.IDENTIFYING_FIELDS
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural guard: every string field of every `*Input` model under
 * `feature/ai/tools/` must be classified as either identifying or free text.
 *
 * A new string argument that is neither trips this test, so the maintainer is
 * forced to decide where it belongs before it can leak into a summary.
 */
@Tag("fast")
class ToolCallSummaryAllowListTest {

    @Test
    fun every_string_field_of_every_input_model_is_classified() {
        val repoRoot = File(
            System.getProperty("commonMain.root")
                ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
        ).parentFile.parentFile.parentFile.parentFile
        val toolsDir = File(repoRoot, "shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools")
        assertTrue(toolsDir.isDirectory, "tools dir missing: ${toolsDir.path}")

        val unclassified = mutableListOf<String>()
        var checked = 0

        toolsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val text = file.readText()
                // Match `data class FooInput(` ... `)` (single-line or multi-line until matching paren).
                val classRegex = Regex("""data\s+class\s+(\w+Input)\s*\(([\s\S]*?)\)""")
                classRegex.findAll(text).forEach { m ->
                    val className = m.groupValues[1]
                    val body = m.groupValues[2]
                    // Each property: `val name: Type` possibly with default.
                    val propRegex = Regex("""val\s+(\w+)\s*:\s*(\w+\??)""")
                    propRegex.findAll(body).forEach { p ->
                        val name = p.groupValues[1]
                        val type = p.groupValues[2]
                        if (!type.startsWith("String")) return@forEach
                        checked++
                        if (name !in IDENTIFYING_FIELDS && name !in FREE_TEXT_FIELDS) {
                            unclassified += "${file.name}: $className.$name"
                        }
                    }
                }
            }

        assertTrue(
            checked > 0,
            "no *Input string fields scanned — the guard is vacuous",
        )
        assertTrue(
            unclassified.isEmpty(),
            "unclassified string fields in *Input models (add to IDENTIFYING_FIELDS " +
                "or FREE_TEXT_FIELDS in ToolCallSummary.kt):\n" +
                unclassified.joinToString("\n") { "  $it" },
        )
    }
}
