package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesInvariants
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.model.TaskSharedField
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [TaskSeriesInvariants] — the rules a series keeps.
 *
 * The invariant is that the fields describing *the work* say the same thing on
 * every row of a series. These tests pin what "the same thing" means, what
 * breaks it, and what a repair looks like.
 */
@Tag("fast")
class TaskSeriesInvariantsTest {

    private val alice = UserId("alice")
    private val seriesId = TaskSeriesId("series-1")
    private val fixedInstant = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun member(
        id: String,
        title: String = "Pay the rent",
        priority: TaskPriority = TaskPriority.None,
        series: TaskSeriesId? = seriesId,
    ) = Task(
        id = TaskId(id),
        title = title,
        priority = priority,
        seriesId = series,
        userId = alice,
        createdAt = fixedInstant,
        updatedAt = fixedInstant,
    )

    // ─── A series that agrees ─────────────────────────────────────────────

    @Test
    fun `a series whose rows agree has no divergence`() {
        val members = listOf(member("s1"), member("s2"), member("s3"))

        assertEquals(
            emptyList(),
            TaskSeriesInvariants.divergedFields(members),
            "three rows that say the same thing about the work are one work",
        )
    }

    @Test
    fun `a series of one member agrees with itself`() {
        assertEquals(emptyList(), TaskSeriesInvariants.divergedFields(listOf(member("s1"))))
    }

    // ─── A series that disagrees ──────────────────────────────────────────

    @Test
    fun `two titles are a divergence`() {
        val members = listOf(member("s1"), member("s2", title = "Pay the rent in full"))

        assertEquals(
            listOf(TaskSharedField.Title),
            TaskSeriesInvariants.divergedFields(members),
            "a series with two titles says two things about one job",
        )
    }

    @Test
    fun `two priorities are a divergence`() {
        val members = listOf(member("s1"), member("s2", priority = TaskPriority.High))

        assertEquals(listOf(TaskSharedField.Priority), TaskSeriesInvariants.divergedFields(members))
    }

    @Test
    fun `the shared fields are the ones the policy names`() {
        // Every field of the policy is covered by the comparison — a field added
        // to the policy is compared without touching this file.
        val members = listOf(member("s1"), member("s2"))
        val compared = TaskSeriesInvariants.sharedFieldsOf(members.first()).keys

        assertEquals(TaskSharedField.entries.toSet(), compared)
    }

    // ─── The repair ────────────────────────────────────────────────────────

    @Test
    fun `aligning gives every member the shared fields of the source`() {
        val source = member("s1", title = "Pay the rent in full", priority = TaskPriority.High)
        val members = listOf(source, member("s2"), member("s3", title = "Pay the rent"))

        val aligned = TaskSeriesInvariants.alignedTo(members, source)

        assertTrue(
            aligned.all { it.title == "Pay the rent in full" && it.priority == TaskPriority.High },
            "after the repair every row says the same thing about the work",
        )
    }

    @Test
    fun `aligning keeps what belongs to the occurrence`() {
        val source = member("s1", title = "Pay the rent in full")
        val members = listOf(source, member("s2").copy(dueDate = kotlinx.datetime.LocalDate(2026, 10, 12)))

        val aligned = TaskSeriesInvariants.alignedTo(members, source)

        assertEquals(
            kotlinx.datetime.LocalDate(2026, 10, 12),
            aligned[1].dueDate,
            "the date belongs to the occurrence, not to the work",
        )
    }

    @Test
    fun `only the members that differ are realigned`() {
        val source = member("s1", title = "Changed")
        val members = listOf(
            source,
            member("s2", title = "Changed"),
            member("s3", title = "Something else"),
        )

        val toRealign = TaskSeriesInvariants.membersToRealign(members, source)

        assertEquals(
            listOf("s3"),
            toRealign.map { it.id.value },
            "a member that already agrees is not written again",
        )
    }

    @Test
    fun `the source is never among the members to realign`() {
        val source = member("s1", title = "Changed")
        val members = listOf(source, member("s2", title = "Something else"))

        val toRealign = TaskSeriesInvariants.membersToRealign(members, source)

        assertTrue(toRealign.none { it.id == source.id }, "the written row is the source, not a target")
    }

    // ─── One series, or two ────────────────────────────────────────────────

    @Test
    fun `rows with one series id are one series`() {
        val members = listOf(member("s1"), member("s2"))

        assertTrue(TaskSeriesInvariants.isOneSeries(members))
        assertEquals(seriesId, TaskSeriesInvariants.seriesIdOf(members))
    }

    @Test
    fun `rows with two series ids are not one series`() {
        val members = listOf(member("s1"), member("s2", series = TaskSeriesId("series-2")))

        assertTrue(
            !TaskSeriesInvariants.isOneSeries(members),
            "a batch with two ids is two series written at once",
        )
    }

    @Test
    fun `rows without a series id are not a series`() {
        val members = listOf(member("s1", series = null), member("s2", series = null))

        assertTrue(
            TaskSeriesInvariants.isOneSeries(members),
            "a batch of ordinary tasks is a batch, and the caller may write it",
        )
        assertEquals(null, TaskSeriesInvariants.seriesIdOf(members))
    }
}
