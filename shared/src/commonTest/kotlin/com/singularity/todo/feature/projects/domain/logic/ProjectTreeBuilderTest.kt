package com.singularity.todo.feature.projects.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The arrangement of the projects into the menu's tree.
 *
 * The rules are asserted one at a time, because each of them is a way the tree
 * can go wrong quietly: a child that ends up beside its parent, a project that
 * is nowhere, a cycle that hangs the menu.
 */
@Tag("fast")
class ProjectTreeBuilderTest {

    @Test
    fun `a child sits under its parent`() {
        val nodes = ProjectTreeBuilder.build(
            projects = listOf(
                project("root", "Product"),
                project("child", "Website", parent = "root"),
            ),
            collapsedIds = emptySet(),
        )

        assertEquals(listOf("root", "child"), nodes.map { it.id.value })
        assertEquals(0, nodes[0].depth)
        assertEquals(1, nodes[1].depth)
    }

    @Test
    fun `a project whose parent is gone is a root`() {
        val nodes = ProjectTreeBuilder.build(
            projects = listOf(project("orphan", "Orphan", parent = "missing")),
            collapsedIds = emptySet(),
        )

        assertEquals(1, nodes.size)
        assertEquals(0, nodes[0].depth, "a project whose parent is gone belongs at the top level")
    }

    @Test
    fun `a closed project is not in the tree`() {
        val nodes = ProjectTreeBuilder.build(
            projects = listOf(
                project("open", "Open"),
                project("closed", "Closed", closed = true),
            ),
            collapsedIds = emptySet(),
        )

        assertEquals(listOf("open"), nodes.map { it.id.value })
    }

    @Test
    fun `a project without children carries no expander`() {
        val nodes = ProjectTreeBuilder.build(
            projects = listOf(
                project("root", "Product"),
                project("childless", "Childless", parent = "root"),
            ),
            collapsedIds = emptySet(),
        )

        assertTrue(nodes.first { it.id.value == "root" }.hasChildren)
        assertFalse(nodes.first { it.id.value == "childless" }.hasChildren)
    }

    @Test
    fun `a collapsed project hides its children`() {
        val nodes = ProjectTreeBuilder.build(
            projects = listOf(
                project("root", "Product"),
                project("child", "Website", parent = "root"),
            ),
            collapsedIds = setOf(ProjectId("root")),
        )

        assertEquals(listOf("root"), nodes.map { it.id.value })
        assertTrue(nodes.single().hasChildren, "the project still has children — it just hides them")
        assertFalse(nodes.single().isExpanded)
    }

    @Test
    fun `a cycle does not hang the tree and loses nothing`() {
        val nodes = ProjectTreeBuilder.build(
            projects = listOf(
                project("a", "A", parent = "b"),
                project("b", "B", parent = "a"),
            ),
            collapsedIds = emptySet(),
        )

        assertEquals(
            setOf("a", "b"),
            nodes.map { it.id.value }.toSet(),
            "both projects of a cycle are still reachable",
        )
    }

    private fun project(
        id: String,
        name: String,
        parent: String? = null,
        closed: Boolean = false,
    ): Project = Project(
        id = ProjectId(id),
        name = name,
        color = 0,
        createdAt = NOW,
        updatedAt = NOW,
        parentId = parent?.let { ProjectId(it) },
        closedAt = if (closed) NOW else null,
        userId = UserId("test-user"),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
    }
}
