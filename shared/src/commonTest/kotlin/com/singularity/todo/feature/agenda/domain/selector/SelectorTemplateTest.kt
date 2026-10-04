package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The selector-parameter configurator: the editor picks a [SelectorTemplate],
 * the user picks values, [ConfigurableSelector.resolve] turns the two into a
 * concrete [Selector].
 *
 * The property that makes this worth a test is the negative one. `Selector.Tags`
 * with an empty id set matches **no task**, so resolving an empty — or
 * unavailable — selection must produce `null` rather than a section that renders
 * as a permanently empty header.
 */
class SelectorTemplateTest {

    @Test
    fun `fixed templates need no parameters and resolve to themselves`() {
        val fixed = SelectorTemplate.catalogue.filterIsInstance<SelectorTemplate.Fixed>()
        assertTrue(fixed.isNotEmpty(), "the editor must still offer the parameterless types")
        for (template in fixed) {
            assertFalse(template.requiresParameters, "${template.label} should not open a second step")
            assertEquals(template.selector, template.resolve(emptySet()))
        }
    }

    @Test
    fun `parameterized templates declare that they need parameters`() {
        val parameterized = SelectorTemplate.catalogue.filter { it.requiresParameters }
        assertEquals(
            setOf(
                SelectorTemplate.ByTags::class.simpleName,
                SelectorTemplate.ByProjects::class.simpleName,
                SelectorTemplate.ByPriority::class.simpleName,
                SelectorTemplate.ByStatus::class.simpleName,
            ),
            parameterized.map { it::class.simpleName }.toSet(),
            "the editor must be able to build tag-, project-, priority- and status-filtered sections",
        )
    }

    @Test
    fun `by tag resolves the chosen tag ids`() {
        val config = ConfigurableSelector(
            template = SelectorTemplate.ByTags(),
            options = listOf(SelectorOption("tag-work", "work"), SelectorOption("tag-home", "home")),
        )
        assertEquals(
            Selector.Tags(setOf(TagId("tag-work"), TagId("tag-home"))),
            config.resolve(setOf("tag-work", "tag-home")),
        )
    }

    @Test
    fun `by project resolves the chosen project ids`() {
        val config = ConfigurableSelector(
            template = SelectorTemplate.ByProjects,
            options = listOf(SelectorOption("p1", "Alpha"), SelectorOption("p2", "Beta")),
        )
        assertEquals(Selector.Projects(setOf(ProjectId("p1"))), config.resolve(setOf("p1")))
    }

    @Test
    fun `by priority resolves enum names`() {
        val config = ConfigurableSelector(
            template = SelectorTemplate.ByPriority(),
            options = TaskPriority.entries.map { SelectorOption(it.name, it.name) },
        )
        assertEquals(
            Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Urgent), atMost = true),
            config.resolve(setOf("High", "Urgent")),
        )
    }

    @Test
    fun `by status resolves enum names`() {
        val config = ConfigurableSelector(
            template = SelectorTemplate.ByStatus,
            options = TaskStatus.entries.map { SelectorOption(it.name, it.name) },
        )
        assertEquals(
            Selector.Statuses(setOf(TaskStatus.Completed)),
            config.resolve(setOf("Completed")),
        )
    }

    @Test
    fun `an empty selection resolves to null rather than a section that matches nothing`() {
        for (template in SelectorTemplate.catalogue.filter { it.requiresParameters }) {
            assertNull(
                template.resolve(emptySet()),
                "${template.label}: Selector.Tags(emptySet()) matches no task — the editor must " +
                    "refuse the section instead of adding an always-empty header",
            )
        }
    }

    @Test
    fun `ids that are not in the option list are dropped`() {
        // The user had the picker open when a tag was deleted. Resolving the
        // stale id would build a selector referencing a row that no longer
        // exists — a section that silently matches nothing, with no error.
        val config = ConfigurableSelector(
            template = SelectorTemplate.ByTags(),
            options = listOf(SelectorOption("tag-work", "work")),
        )
        assertNull(config.resolve(setOf("tag-deleted")))
        assertEquals(
            Selector.Tags(setOf(TagId("tag-work"))),
            config.resolve(setOf("tag-work", "tag-deleted")),
        )
    }

    @Test
    fun `the catalogue has unique labels`() {
        val labels = SelectorTemplate.catalogue.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate labels make the picker ambiguous: $labels")
    }
}
