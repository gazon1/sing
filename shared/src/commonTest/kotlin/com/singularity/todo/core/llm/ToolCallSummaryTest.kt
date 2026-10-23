@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.llm

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a card is allowed to quote.
 *
 * The rule under test is the one that keeps the user's content off a screen that was about
 * to be shared: a summary names the fields and quotes the values that are too short to be
 * prose, and replaces everything else with an ellipsis.
 */
@Tag("fast")
class ToolCallSummaryTest {

    @Test
    fun aToolWithNoArgumentsIsNamed() {
        assertEquals("list_tasks", toolCallSummary("list_tasks", null))
        assertEquals("list_tasks", toolCallSummary("list_tasks", ""))
    }

    @Test
    fun shortValuesAreQuoted() {
        val summary = toolCallSummary("get_task", """{"id":"t-42"}""")

        assertTrue(summary.contains("id=t-42"), "The identifier is named: $summary")
    }

    @Test
    fun longValuesAreNotQuoted() {
        val body = "x".repeat(400)
        val summary = toolCallSummary("update_note", """{"body":"$body"}""")

        assertTrue(!summary.contains("xxxx"), "The note body is not quoted: $summary")
        assertTrue(summary.contains("body=…"), "But the field is named: $summary")
    }

    @Test
    fun aValueSpanningLinesIsNotQuoted() {
        val summary = toolCallSummary("update_note", """{"body":"first line\nsecond line"}""")

        assertTrue(!summary.contains("first line"), "A document is not a label: $summary")
    }

    @Test
    fun theSummaryIsBounded() {
        val many = (1..40).joinToString(",") { """"field$it":"value$it"""" }
        val summary = toolCallSummary("update_task", "{$many}")

        assertTrue(
            summary.length <= TOOL_CALL_SUMMARY_MAX_LENGTH + 1,
            "The summary is a label, not a document: ${summary.length} characters",
        )
    }

    @Test
    fun unreadableArgumentsDoNotBreakTheSummary() {
        val summary = toolCallSummary("update_task", "{not json")

        assertTrue(summary.startsWith("update_task"), "The tool is still named: $summary")
    }

    @Test
    fun theToolNameIsAlwaysThere() {
        assertTrue(toolCallSummary("get_note", """{"id":"n1"}""").startsWith("get_note"))
    }
}
