package com.singularity.todo.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tool declared at the top level of the file.
 */
private class FileLevelTool : SimpleTool<Unit>(TypeToken.of(Unit::class.java), NAME, DESCRIPTION) {
    override suspend fun execute(args: Unit): String = "{}"

    companion object {
        const val NAME = "file_level_tool"
        const val DESCRIPTION = "A tool declared at the top level of the file."
    }
}

/**
 * The kill switch, at the level of the rule it is written in.
 *
 * A tool is switched off by a flag keyed by its ID — the `NAME` constant of the tool
 * class. If the ID cannot be read, the flag cannot match, and the tool stays exposed
 * while the operator believes it is off. That failure is silent, so it is pinned here.
 *
 * Both shapes of tool class are covered, because the shape is invisible in the source of
 * the tool — `const val NAME` in a companion reads the same either way, and only the
 * bytecode differs. An earlier version looked for the constant in one place only, raised
 * out of the lookup when it was not there, and returned `null`: every tool lost its ID,
 * and with it the kill switch.
 */
@Tag("fast")
class AiToolFiltersTest {

    /** A tool declared inside another class. */
    private class NestedTool : SimpleTool<Unit>(TypeToken.of(Unit::class.java), NAME, DESCRIPTION) {
        override suspend fun execute(args: Unit): String = "{}"

        companion object {
            const val NAME = "nested_tool"
            const val DESCRIPTION = "A tool declared inside another class."
        }
    }

    @Test
    fun `the id of a file-level tool is its NAME constant`() {
        assertEquals(FileLevelTool.NAME, FileLevelTool().toolId())
    }

    @Test
    fun `the id of a nested tool is its NAME constant`() {
        assertEquals(NestedTool.NAME, NestedTool().toolId())
    }

    @Test
    fun `a tool with no flag is enabled`() {
        val tools = listOf(FileLevelTool(), NestedTool())

        val enabled = filterTools(tools, emptyMap())

        assertEquals(2, enabled.size)
    }

    @Test
    fun `a tool switched off by its flag is filtered out`() {
        val tools = listOf(FileLevelTool(), NestedTool())

        val enabled = filterTools(tools, mapOf(NestedTool.NAME to false))

        assertEquals(listOf(FileLevelTool.NAME), enabled.map { it.toolId() })
    }

    @Test
    fun `the flags of every shape of tool are honoured`() {
        val tools = listOf(FileLevelTool(), NestedTool())

        val enabled = filterTools(tools, mapOf(FileLevelTool.NAME to false, NestedTool.NAME to false))

        assertTrue(enabled.isEmpty(), "both tools were switched off, so none may stay exposed")
    }
}
