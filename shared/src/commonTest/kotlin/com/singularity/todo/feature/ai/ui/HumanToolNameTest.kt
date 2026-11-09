package com.singularity.todo.feature.ai.ui

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tool-call line, said as words.
 *
 * The label is the only part of a tool call the reader is given as words, so the wording is the
 * whole rendering: the verb has to read as a gerund, everything after it has to survive as the noun
 * it is, and a verb the table has not met yet still has to read as a word.
 */
@Tag("fast")
class HumanToolNameTest {
    @Test
    fun everyVerbInTheTableReadsAsItsGerund() {
        val expected =
            listOf(
                "create_task" to "Creating task",
                "update_task" to "Updating task",
                "delete_task" to "Deleting task",
                "list_tasks" to "Listing tasks",
                "get_task" to "Getting task",
                "search_tasks" to "Searching tasks",
                "refine_task" to "Refining task",
                "generate_description" to "Generating description",
                "decompose_task" to "Decomposing task",
                "cluster_tasks" to "Clustering tasks",
                "suggest_tags" to "Suggesting tags",
                "improve_note" to "Improving note",
                "summarize_note" to "Summarizing note",
                "extract_actions" to "Extracting actions",
                "rewrite_note" to "Rewriting note",
                "pick_time" to "Picking time",
                "write_task_proposal" to "Writing task proposal",
            )

        expected.forEach { (raw, human) -> assertEquals(human, humanToolName(raw), raw) }
    }

    @Test
    fun aVerbOnItsOwnIsJustTheGerund() {
        assertEquals("Creating", humanToolName("create"))
    }

    @Test
    fun everyPartAfterTheVerbBecomesTheNoun() {
        assertEquals("Creating task with due date", humanToolName("create_task_with_due_date"))
    }

    @Test
    fun aVerbTheTableDoesNotKnowIsCapitalisedRatherThanDropped() {
        assertEquals("Frobnicate task", humanToolName("frobnicate_task"))
        assertEquals("Smart rewrite", humanToolName("smart_rewrite"))
    }

    @Test
    fun theVerbIsMatchedRegardlessOfCase() {
        assertEquals("Listing tasks", humanToolName("LIST_tasks"))
    }
}
