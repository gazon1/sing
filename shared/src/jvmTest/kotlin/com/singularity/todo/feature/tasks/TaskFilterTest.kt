package com.singularity.todo.feature.tasks

import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskFilterTest {

    @Test
    fun `TaskFilter - Today filter has correct key`() {
        val filter = TaskFilter.Today
        assertEquals("Today", filter.toString())
    }

    @Test
    fun `TaskFilter - Upcoming filter has correct key`() {
        val filter = TaskFilter.Upcoming
        assertEquals("Upcoming", filter.toString())
    }

    @Test
    fun `TaskFilter - Someday filter has correct key`() {
        val filter = TaskFilter.Someday
        assertEquals("Someday", filter.toString())
    }

    @Test
    fun `TaskFilter - Inbox filter has correct key`() {
        val filter = TaskFilter.Inbox
        assertEquals("Inbox", filter.toString())
    }

    @Test
    fun `TaskFilter - Trash filter has correct key`() {
        val filter = TaskFilter.Trash
        assertEquals("Trash", filter.toString())
    }

    @Test
    fun `TaskFilter - All filter has correct key`() {
        val filter = TaskFilter.All
        assertEquals("All", filter.toString())
    }

    @Test
    fun `TaskFilter - ByProject contains projectId`() {
        val projectId = ProjectId.fromString("proj-123")
        val filter = TaskFilter.ByProject(projectId)
        assertTrue(filter.toString().contains("proj-123"))
    }

    @Test
    fun `TaskFilter - ByTag contains tagId`() {
        val tagId = TagId.fromString("tag-456")
        val filter = TaskFilter.ByTag(tagId)
        assertTrue(filter.toString().contains("tag-456"))
    }

    @Test
    fun `TaskFilter - Search contains query`() {
        val filter = TaskFilter.Search("test query")
        assertTrue(filter.toString().contains("test query"))
    }
}
