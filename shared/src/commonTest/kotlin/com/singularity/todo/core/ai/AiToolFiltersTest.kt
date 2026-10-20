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
 * The kill switch, and the name it keys on.
 *
 * The switch matches a flag against the tool's name. The name is read from the tool's
 * descriptor — the name the tool is registered under, the name the catalogue declares,
 * the name the agent calls — and *that* the name equals the class's `NAME` constant is
 * an invariant, not a coincidence.
 *
 * It was a coincidence once, and the switch was inert because of it: the name used to be
 * read out of the bytecode, where the Kotlin compiler puts a `const val` in a companion
 * object on the **containing class** — so the lookup raised `NoSuchFieldException`, the
 * enclosing `catch` turned it into `null`, and a tool with no name has no flag key. No
 * flag ever matched a tool (ADR 2026-10-20-the-kill-switch-was-never-applied).
 *
 * Both shapes of tool class are covered here, because the shape is invisible in the
 * source of the tool: `const val NAME` in a companion reads the same either way, and
 * only the bytecode differs.
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
    fun `the name of a file-level tool is its NAME constant`() {
        assertEquals(FileLevelTool.NAME, FileLevelTool().descriptor.name)
    }

    @Test
    fun `the name of a nested tool is its NAME constant`() {
        assertEquals(NestedTool.NAME, NestedTool().descriptor.name)
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

        assertEquals(listOf(FileLevelTool.NAME), enabled.map { it.descriptor.name })
    }

    @Test
    fun `the flags of every shape of tool are honoured`() {
        val tools = listOf(FileLevelTool(), NestedTool())

        val enabled = filterTools(tools, mapOf(FileLevelTool.NAME to false, NestedTool.NAME to false))

        assertTrue(enabled.isEmpty(), "both tools were switched off, so none may stay exposed")
    }

    @Test
    fun `a flag for a tool that does not exist changes nothing`() {
        val tools = listOf(FileLevelTool(), NestedTool())

        val enabled = filterTools(tools, mapOf("no_such_tool" to false))

        assertEquals(2, enabled.size, "a flag that matches no tool must not remove another one")
    }
}
