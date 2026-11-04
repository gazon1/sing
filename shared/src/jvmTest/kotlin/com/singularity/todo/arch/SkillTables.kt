package com.singularity.todo.arch

import java.io.File

/**
 * The tables of a skill, read the way a Markdown reader reads them.
 *
 * A skill publishes its contract in tables — beside its body, in a document of its own.
 * A test that has to check those tables against the code has to find the *right* table:
 * the document holds several, and they have different shapes. Matching on the header row
 * is what makes the read unambiguous — a row is only a row of this table if it follows
 * this table's header.
 *
 * Reading the table by its header also keeps the reader honest: a table whose header
 * changed is a table the test no longer finds, and the test says so instead of
 * silently reading nothing.
 */
internal object SkillTables {
    /**
     * The document that publishes the text-field contract — the skill's field tables.
     *
     * The tables live beside the skill's body, in `FIELDS.md`: the body carries the
     * method, the document carries the per-field data.
     */
    private const val TEXT_FIELD_CONTRACT = "singularity-todo-text-field-states/FIELDS.md"

    /**
     * The rows of a table in the text-field contract.
     *
     * @param header the header cells, in order — the table's identity.
     * @return the rows that follow the header, each a list of trimmed cells.
     * @throws IllegalStateException if the contract has no table with that header.
     */
    fun table(vararg header: String): List<List<String>> = tableIn(TEXT_FIELD_CONTRACT, *header)

    /**
     * The rows of a table in the named document.
     *
     * @param skill the file's path under the skills root.
     * @param header the header cells, in order — the table's identity.
     * @return the rows that follow the header, each a list of trimmed cells.
     * @throws IllegalStateException if the document has no table with that header.
     */
    fun tableIn(
        skill: String,
        vararg header: String,
    ): List<List<String>> {
        val expected = header.map { it.trim() }
        val lines = skillFile(skill).readLines()
        val headerIndex = lines.indexOfFirst { line -> cells(line) == expected }
        require(headerIndex >= 0) {
            "the document has no table with the header ${expected.joinToString(" | ")}"
        }

        return lines
            .drop(headerIndex + 1)
            .takeWhile { it.trimStart().startsWith("|") }
            .map { line -> cells(line) }
            .filter { row -> row.size == expected.size }
    }

    /** The text-field contract document itself. */
    fun skillFile(): File = skillFile(TEXT_FIELD_CONTRACT)

    /** The file of the named skill. */
    fun skillFile(skill: String): File =
        File(
            System.getProperty("skills.root")
                ?: error("skills.root system property is not set — see shared/build.gradle.kts"),
            skill,
        )

    /**
     * The cells of a Markdown table row.
     *
     * A line that is not a table row has no cells.
     */
    private fun cells(line: String): List<String> {
        if (!line.trimStart().startsWith("|")) return emptyList()
        return line
            .trim()
            .trim('|')
            .split("|")
            .map { cell -> cell.trim() }
    }
}
