package com.singularity.todo.feature.agenda.presentation.nav

import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The Stagnant drill-down must evaluate the threshold the user configured.
 *
 * ## What went wrong, and why this test exists
 *
 * `AgendaStartRoute.Stagnant` used to render `AgendaPresets.byStagnant()` with no
 * argument — the 21-day default — while the Statistics card that opens it counted
 * with the stored setting. On a fresh install the two agree because the default *is*
 * the setting; on any install where the user moved the slider they disagree, and the
 * disagreement is silent: the card says one number and the list holds another set.
 *
 * The screen's part is a single call, [stagnantDefinition]. Pinning *it* is what makes
 * the behaviour testable without mounting Compose — an earlier flow test for this
 * passed with the fix reverted, because the fixtures could not tell the two windows
 * apart. A test that cannot fail is worse than no test: it reports green on the
 * defect it was written for.
 *
 * ## Why the assertion is about the selector, not the count
 *
 * The definition's only job is to carry the right window into the selector. Asserting
 * bucket counts here would re-test `Selector.Stagnant`, which
 * `HealthClassificationAgreementTest` already pins against the statistics path.
 */
@Tag("fast")
@Epic("Agenda")
@Feature("Navigation")
class StagnantDefinitionTest {
    private fun AgendaDefinition.window(): Duration {
        val selector = sections.single().selector
        assertTrue(selector is Selector.Stagnant, "the definition holds one Stagnant section, found $selector")
        return selector.threshold
    }

    @Test
    fun `a configured threshold reaches the definition`() {
        assertEquals(60.days, stagnantDefinition(stalenessDays = 60).window())
        assertEquals(7.days, stagnantDefinition(stalenessDays = 7).window())
    }

    /**
     * A stored `0` is "unset", and the definition must say the default rather than
     * build a zero-day window — which would make every undated task stagnant the day
     * it was created.
     */
    @Test
    fun `an unset threshold becomes the default, not a zero-day window`() {
        assertEquals(
            Selector.Stagnant.DEFAULT_STAGNATION_THRESHOLD,
            stagnantDefinition(stalenessDays = 0).window(),
        )
    }

    /** A negative setting is not a window either; the answer is the default. */
    @Test
    fun `a negative threshold becomes the default`() {
        assertEquals(
            Selector.Stagnant.DEFAULT_STAGNATION_THRESHOLD,
            stagnantDefinition(stalenessDays = -1).window(),
        )
    }

    /**
     * The end-to-end consequence, stated once: a task idle for 30 days is selected by
     * a 60-day definition and not by a 21-day one.
     *
     * This is the pair the card and the old list disagreed about.
     */
    @Test
    fun `the configured window decides which tasks the section holds`() {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val thirtyDaysIdle =
            Task(
                id = TaskId("t1"),
                title = "idle 30 days",
                dueDate = null,
                createdAt = now - 30.days,
                updatedAt = now - 30.days,
                userId =
                    com.singularity.todo.core.ids
                        .UserId("u1"),
            )
        val context =
            SelectorContext(
                today = LocalDate(2026, 9, 16),
                now = now,
                blockedIds = emptySet(),
                lastActivityOf = { it.updatedAt },
            )

        val wide = stagnantDefinition(stalenessDays = 60).sections.single().selector
        val narrow = stagnantDefinition(stalenessDays = 21).sections.single().selector

        assertTrue(!wide.matches(thirtyDaysIdle, context), "30 days idle is under a 60-day window")
        assertTrue(narrow.matches(thirtyDaysIdle, context), "30 days idle is over a 21-day window")
    }
}
