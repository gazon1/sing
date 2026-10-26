@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.llm

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a card is allowed to quote.
 *
 * The rule under test is the one that keeps the user's content off a screen that was about to
 * be shared: a summary names the fields, quotes a value only when its field identifies and the
 * value is short and single-line, and replaces every other value with an ellipsis.
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

    /**
     * A short, single-line free-text value is elided too — T062's gap.
     *
     * The rule used to be length-based, so a one-line note body of four words was quoted
     * straight into the card. FR-020 says the summary carries only identifying information;
     * the field decides now, not the value's size.
     */
    @Test
    fun aShortFreeTextValueIsNotQuoted() {
        val summary = toolCallSummary("update_note", """{"bodyMarkdown":"Buy milk and eggs"}""")

        assertFalse(summary.contains("Buy milk"), "The reader's words are not a label: $summary")
        assertTrue(summary.contains("bodyMarkdown=…"), "The field is named and elided: $summary")
    }

    @Test
    fun aShortDescriptionIsNotQuoted() {
        val summary = toolCallSummary("create_task", """{"title":"Plan the week","description":"Ship it"}""")

        assertTrue(summary.contains("title=Plan the week"), "A title identifies the thing: $summary")
        assertFalse(summary.contains("Ship it"), "A description is content: $summary")
        assertTrue(summary.contains("description=…"), summary)
    }

    @Test
    fun anIdentifyingValueIsQuotedWhenShortAndSingleLine() {
        val summary = toolCallSummary(
            "update_task",
            """{"taskId":"t-42","status":"Completed","priority":2,"isPinned":true}""",
        )

        assertTrue(summary.contains("taskId=t-42"), summary)
        assertTrue(summary.contains("status=Completed"), summary)
        assertTrue(summary.contains("priority=2"), "A number is not free text: $summary")
        assertTrue(summary.contains("isPinned=true"), "A flag is not free text: $summary")
    }

    @Test
    fun aValueSpanningLinesIsNotQuoted() {
        // An identifying field, so this exercises the multi-line rule and not the field rule.
        val summary = toolCallSummary("update_task", """{"title":"first line\nsecond line"}""")

        assertTrue(!summary.contains("first line"), "A document is not a label: $summary")
        assertTrue(summary.contains("title=…"), summary)
    }

    @Test
    fun anIdentifyingValuePastTheLimitIsNotQuoted() {
        // Also an identifying field, so this exercises the length rule itself.
        val summary = toolCallSummary("update_task", """{"title":"${"x".repeat(61)}"}""")

        assertTrue(!summary.contains("xxx"), "Past the limit is a document: $summary")
        assertTrue(summary.contains("title=…"), summary)
    }

    @Test
    fun theSummaryIsBounded() {
        // Identifying fields with values at the length limit, so the quoting rule does not
        // shrink the summary before the length bound can apply.
        val value = "v".repeat(60)
        val many = listOf("title", "name", "label", "slug", "kind", "status", "tone", "verb")
            .joinToString(",") { """"$it":"$value"""" }
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
