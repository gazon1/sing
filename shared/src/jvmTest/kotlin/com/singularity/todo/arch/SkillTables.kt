package com.singularity.todo.arch

import java.io.File

/**
 * The tables of the text-field skill, read the way a Markdown reader reads them.
 *
 * The skill publishes the text-field contract in tables. A test that has to check
 * those tables against the code has to find the *right* table: the skill holds several,
 * and they have different shapes. Matching on the header row is what makes the read
 * unambiguous — a row is only a row of this table if it follows this table's header.
 *
 * Reading the table by its header also keeps the reader honest: a table whose header
 * changed is a table the test no longer finds, and the test says so instead of
 * silently reading nothing.
 */
internal object SkillTables {

    /** The skill that publishes the text-field contract. */
    private const val SKILL = "singularity-todo-text-field-states/SKILL.md"

    /**
     * The rows of a table in the skill.
     *
     * @param header the header cells, in order — the table's identity.
     * @return the rows that follow the header, each a list of trimmed cells.
     * @throws IllegalStateException if the skill has no table with that header.
     */
    fun table(vararg header: String): List<List<String>> {
        val expected = header.map { it.trim() }
        val lines = skillFile().readLines()
        val headerIndex = lines.indexOfFirst { line -> cells(line) == expected }
        require(headerIndex >= 0) {
            "the skill has no table with the header ${expected.joinToString(" | ")}"
        }

        return lines.drop(headerIndex + 1)
            .takeWhile { it.trimStart().startsWith("|") }
            .map { line -> cells(line) }
            .filter { row -> row.size == expected.size }
    }

    /** The skill file itself. */
    fun skillFile(): File = File(
        System.getProperty("skills.root")
            ?: error("skills.root system property is not set — see shared/build.gradle.kts"),
        SKILL,
    )

    /**
     * The cells of a Markdown table row.
     *
     * A line that is not a table row has no cells.
     */
    private fun cells(line: String): List<String> {
        if (!line.trimStart().startsWith("|")) return emptyList()
        return line.trim().trim('|').split("|").map { cell -> cell.trim() }
    }
}
