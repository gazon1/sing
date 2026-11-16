package com.singularity.todo.feature.ai.prompts

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("fast")
class PromptsTest {
    @Test
    fun `refineUser includes title and description`() {
        val result = Prompts.refineUser("Buy milk", "Get 2% milk")
        assertEquals("Title: Buy milk\nDescription: Get 2% milk", result)
    }

    @Test
    fun `refineUser handles null description`() {
        val result = Prompts.refineUser("Buy milk", null)
        assertEquals("Title: Buy milk\nDescription: (none)", result)
    }

    @Test
    fun `clusterTasksUser formats task list`() {
        val result = Prompts.clusterTasksUser(listOf("Task A", "Task B"))
        assertTrue(result.contains("- Task A"))
        assertTrue(result.contains("- Task B"))
    }

    @Test
    fun `clusterNotesUser formats note list`() {
        val result = Prompts.clusterNotesUser(listOf("Note 1"))
        assertTrue(result.contains("- Note 1"))
    }

    @Test
    fun `weeklyPlanUser states the mode, the day and the facts`() {
        val result =
            Prompts.weeklyPlanUser(
                mode = "morning",
                today = "2026-03-10",
                timeZone = "Europe/Berlin",
                tasks = """[{"id":"t1","title":"Task A","health":"overdue"}]""",
                omitted = 0,
            )
        assertTrue(result.contains("Mode: morning"))
        assertTrue(result.contains("Today: 2026-03-10 (time zone: Europe/Berlin)"))
        assertTrue(result.contains("\"title\":\"Task A\""))
        assertTrue(result.contains("\"health\":\"overdue\""))
    }

    @Test
    fun `weeklyPlanUser says when the selection was truncated`() {
        val result =
            Prompts.weeklyPlanUser(
                mode = "morning",
                today = "2026-03-10",
                timeZone = "UTC",
                tasks = "[]",
                omitted = 7,
            )
        assertTrue(result.contains("7 more task(s)"), result)
    }

    @Test
    fun `weeklyPlanUser carries completions and named tasks`() {
        val result =
            Prompts.weeklyPlanUser(
                mode = "evening",
                today = "2026-03-10",
                timeZone = "UTC",
                tasks = "[]",
                omitted = 0,
                completedToday = """[{"id":"t2","title":"Done"}]""",
                namedTasks = listOf("Loosely named task"),
            )
        assertTrue(result.contains("Completed today (JSON)"))
        assertTrue(result.contains("\"title\":\"Done\""))
        assertTrue(result.contains("- Loosely named task"))
    }

    @Test
    fun `projectReviewUser includes project name and tasks`() {
        val result = Prompts.projectReviewUser("My Project", listOf("Task 1", "Task 2"))
        assertTrue(result.contains("Project: My Project"))
        assertTrue(result.contains("- Task 1"))
        assertTrue(result.contains("- Task 2"))
    }

    @Test
    fun `smartRewriteUser wraps raw idea`() {
        val result = Prompts.smartRewriteUser("maybe go to store")
        assertEquals("Raw idea: maybe go to store", result)
    }

    @Test
    fun `generateDescriptionUser passes title through`() {
        val result = Prompts.generateDescriptionUser("Write report")
        assertEquals("Task: Write report", result)
    }

    @Test
    fun `decomposeTaskUser includes null description`() {
        val result = Prompts.decomposeTaskUser("Write report", null)
        assertTrue(result.contains("Task: Write report"))
        assertTrue(result.contains("Description: (none)"))
    }
}
