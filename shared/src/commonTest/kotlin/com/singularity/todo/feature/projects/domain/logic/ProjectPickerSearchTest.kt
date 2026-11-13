package com.singularity.todo.feature.projects.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@Tag("fast")
class ProjectPickerSearchTest {
    @Test
    fun `a root match has no ancestor path`() {
        val root = project("root", "Work")
        val matches = ProjectPickerSearch.filter(listOf(root), "work")

        assertEquals(listOf("root"), matches.map { it.project.id.value })
        assertEquals(emptyList(), matches.single().ancestorNames)
    }

    @Test
    fun `a nested match retains its ancestors without unrelated siblings`() {
        val root = project("root", "Work")
        val android = project("android", "Android", parent = "root")
        val ui = project("ui", "UI", parent = "android")
        val ios = project("ios", "iOS", parent = "root")
        val matches = ProjectPickerSearch.filter(listOf(root, android, ui, ios), "ui")

        assertEquals(listOf("ui"), matches.map { it.project.id.value })
        assertEquals(listOf("Work", "Android"), matches.single().ancestorNames)
    }

    @Test
    fun `same-name projects under different parents keep distinct ancestor paths`() {
        val work = project("work", "Work")
        val home = project("home", "Home")
        val workUi = project("work-ui", "UI", parent = "work")
        val homeUi = project("home-ui", "UI", parent = "home")
        val matches = ProjectPickerSearch.filter(listOf(work, home, workUi, homeUi), "ui")

        assertEquals(listOf("home-ui", "work-ui"), matches.map { it.project.id.value })
        assertEquals(listOf("Home"), matches[0].ancestorNames)
        assertEquals(listOf("Work"), matches[1].ancestorNames)
    }

    @Test
    fun `same-name sibling projects remain individually selectable results`() {
        val work = project("work", "Work")
        val ui1 = project("ui-1", "UI", parent = "work", sortOrder = 0)
        val ui2 = project("ui-2", "UI", parent = "work", sortOrder = 1)
        val matches = ProjectPickerSearch.filter(listOf(work, ui1, ui2), "ui")

        assertEquals(listOf("ui-1", "ui-2"), matches.map { it.project.id.value })
        assertEquals(listOf(listOf("Work"), listOf("Work")), matches.map { it.ancestorNames })
    }

    @Test
    fun `matches follow hierarchy display order for ties`() {
        val root = project("root", "Root", sortOrder = 0)
        val b = project("b", "Task B", parent = "root", sortOrder = 1)
        val a = project("a", "Task A", parent = "root", sortOrder = 1)
        val matches = ProjectPickerSearch.filter(listOf(root, a, b), "task")

        assertEquals(listOf("a", "b"), matches.map { it.project.id.value })
    }

    @Test
    fun `matches follow the same hierarchy order as ProjectTreeBuilder`() {
        val work = project("work", "Work", sortOrder = 2)
        val home = project("home", "Home", sortOrder = 1)
        val workAndroid = project("work-android", "Android", parent = "work", sortOrder = 0)
        val homeAndroid = project("home-android", "Android", parent = "home", sortOrder = 0)
        val projects = listOf(work, workAndroid, home, homeAndroid)

        val matches = ProjectPickerSearch.filter(projects, "android")
        val treeOrder =
            ProjectTreeBuilder
                .build(projects, collapsedIds = emptySet())
                .map { it.id.value }
                .filter { id -> id in setOf("home-android", "work-android") }

        assertEquals(treeOrder, matches.map { it.project.id.value })
    }

    @Test
    fun `no match returns an empty result`() {
        val projects = listOf(project("root", "Work"), project("child", "Android", parent = "root"))

        assertTrue(ProjectPickerSearch.filter(projects, "zzz").isEmpty())
    }

    @Test
    fun `a blank query returns no filtered matches`() {
        val projects = listOf(project("root", "Work"))

        assertTrue(ProjectPickerSearch.filter(projects, "").isEmpty())
        assertTrue(ProjectPickerSearch.filter(projects, "   ").isEmpty())
    }

    @Test
    fun `matches are found at every depth and keep long paths`() {
        val root = project("root", "Work")
        val a = project("a", "Mobile", parent = "root")
        val b = project("b", "Android", parent = "a")
        val c = project("c", "UI", parent = "b")
        val d = project("d", "Compose", parent = "c")
        val matches = ProjectPickerSearch.filter(listOf(root, a, b, c, d), "compose")

        assertEquals(listOf("d"), matches.map { it.project.id.value })
        assertEquals(listOf("Work", "Mobile", "Android", "UI"), matches.single().ancestorNames)
    }

    @Test
    fun `search matches nested projects case-insensitively anywhere in the name`() {
        val root = project("root", "Work")
        val child = project("child", "Airport", parent = "root")
        val matches = ProjectPickerSearch.filter(listOf(root, child), "PORT")

        assertEquals(listOf("child"), matches.map { it.project.id.value })
    }

    @Test
    fun `closed and deleted projects are not returned as matches`() {
        val open = project("open", "Alpha")
        val closed = project("closed", "Alphabet", closed = true)
        val deleted = project("deleted", "Alpine").copy(isDeleted = true, deletedAt = NOW)
        val matches = ProjectPickerSearch.filter(listOf(open, closed, deleted), "alp")

        assertEquals(listOf("open"), matches.map { it.project.id.value })
    }

    @Test
    fun `a matching ancestor is contextual and its matching descendant is still returned`() {
        val root = project("root", "Work")
        val child = project("child", "Workshop", parent = "root")
        val matches = ProjectPickerSearch.filter(listOf(root, child), "work")

        assertEquals(listOf("root", "child"), matches.map { it.project.id.value })
        assertEquals(emptyList<String>(), matches[0].ancestorNames)
        assertEquals(listOf("Work"), matches[1].ancestorNames)
    }

    @Test
    fun `a project with a missing parent is still searchable`() {
        val orphan = project("orphan", "Orphan", parent = "missing")
        val matches = ProjectPickerSearch.filter(listOf(orphan), "orph")

        assertEquals(listOf("orphan"), matches.map { it.project.id.value })
        assertEquals(emptyList(), matches.single().ancestorNames)
    }

    @Test
    fun `cyclic hierarchies do not loop and still return matching projects`() {
        val a = project("a", "Alpha", parent = "b")
        val b = project("b", "Beta", parent = "a")
        val matches = ProjectPickerSearch.filter(listOf(a, b), "a")

        assertEquals(listOf("a", "b"), matches.map { it.project.id.value })
        assertEquals(2, matches.size)
    }

    private fun project(
        id: String,
        name: String,
        parent: String? = null,
        closed: Boolean = false,
        sortOrder: Int = 0,
    ): Project =
        Project(
            id = ProjectId(id),
            name = name,
            color = 0,
            createdAt = NOW,
            updatedAt = NOW,
            parentId = parent?.let { ProjectId(it) },
            closedAt = if (closed) NOW else null,
            sortOrder = sortOrder,
            userId = UserId("test-user"),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-12T12:00:00Z")
    }
}
