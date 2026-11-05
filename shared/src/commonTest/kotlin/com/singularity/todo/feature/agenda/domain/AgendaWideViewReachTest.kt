package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the reach of the wide agenda view — [AgendaPresets.All].
 *
 * The wide view is the seed a new saved view starts from, so its reach is the
 * reach of every saved view built that way. The reach is decided: the view
 * covers the buckets up to and including the next month, and a task due after
 * that is in no section of it.
 *
 * Two things make this worth a test:
 *
 * - The month buckets meet at the edges. Late in a month the week buckets reach
 *   into the next one — on Friday 30 October the current week ends on 1 November
 *   — so a next-month task can be claimed by "This Week" instead of "Next
 *   Month". It still lands in exactly one section, and the test says which.
 * - The reach is a promise. A bucket that no preset uses is a bucket nobody can
 *   reach, so the wide view has to hold one section per bucket.
 *
 * See ADR `2026-11-05-the-wide-view-reaches-next-month`.
 */
@Tag("fast")
class AgendaWideViewReachTest {
    private val now = kotlin.time.Instant.fromEpochMilliseconds(0)

    /** A Wednesday in the middle of a month — the week buckets stay inside it. */
    private val midMonth: LocalDate = LocalDate(2026, 10, 14)

    /** A Friday at the end of a month — the current week runs into the next one. */
    private val lateMonth: LocalDate = LocalDate(2026, 10, 30)

    // ─── Task factory ──────────────────────────────────────────────────────

    private fun task(
        id: String,
        dueDate: LocalDate?,
    ): Task =
        Task(
            id = TaskId(id),
            title = "Task $id",
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            projectId = null,
            parentTaskId = null,
            tags = emptyList(),
            dueDate = dueDate,
            dueTime = null,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            estimateMinutes = null,
            completedAt = null,
            someday = false,
            archivedAt = null,
            isPinned = false,
            blockedBy = emptySet(),
            aiSuppressedTagIds = emptySet(),
            recurrence = null,
            createdAt = now,
            updatedAt = now,
            userId = UserId("profile-personal"),
            serverVersion = 0,
            hlc = null,
        )

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** The names of the sections of [definition] that contain [task]. */
    private fun sectionsContaining(
        definition: AgendaDefinition,
        task: Task,
        today: LocalDate,
    ): List<String> =
        AgendaEvaluator
            .evaluate(listOf(task), definition, today, now)
            .filter { section -> section.tasks.any { it.task.id == task.id } }
            .map { it.name }

    // ─── The reach ─────────────────────────────────────────────────────────

    @Test
    fun `a task due in a later month is in exactly one section of the wide view`() {
        val cases =
            listOf(
                Triple("mid-month today, later in the next month", midMonth, LocalDate(2026, 11, 20)),
                Triple("mid-month today, the last day of the next month", midMonth, LocalDate(2026, 11, 30)),
                Triple("late-month today, the day the week bucket reaches over", lateMonth, LocalDate(2026, 11, 1)),
                Triple("late-month today, the first day of the next week bucket", lateMonth, LocalDate(2026, 11, 2)),
                Triple("late-month today, later in the next month", lateMonth, LocalDate(2026, 11, 20)),
            )

        cases.forEachIndexed { index, (label, today, dueDate) ->
            val task = task("t$index", dueDate)

            val sections = sectionsContaining(AgendaPresets.All, task, today)

            assertEquals(
                1,
                sections.size,
                "a task due in a later month is in exactly one section of the wide view — " +
                    "not in none, and not in two ($label: today $today, due $dueDate, " +
                    "sections $sections)",
            )
        }
    }

    @Test
    fun `a task due in the next month is in the Next Month section of the wide view`() {
        val task = task("next-month", LocalDate(2026, 11, 20))

        assertEquals(
            listOf("Next Month"),
            sectionsContaining(AgendaPresets.All, task, midMonth),
            "a task due in the next month is in exactly one section of the wide view — " +
                "the \"Next Month\" one",
        )
    }

    @Test
    fun `a task due beyond the next month is in no section of the wide view`() {
        val task = task("later", LocalDate(2026, 12, 15))

        assertEquals(
            emptyList(),
            sectionsContaining(AgendaPresets.All, task, midMonth),
            "a task due beyond the end of next month is in no section of the wide view — " +
                "the wide view's reach ends at the end of next month",
        )
    }

    @Test
    fun `every bucket is reachable from a preset`() {
        val sections = AgendaPresets.All.sections

        assertEquals(
            RelativeBucket.entries.size,
            sections.size,
            "the wide view holds one section per bucket — a section whose selector is " +
                "not a plain date bucket cannot be checked this way, and a bucket with " +
                "no section is a bucket nobody can reach",
        )

        val bucketsInWideView =
            sections
                .mapNotNull { (it.selector as? Selector.DateBucket)?.bucket }
                .toSet()

        assertEquals(
            RelativeBucket.entries.toSet(),
            bucketsInWideView,
            "every bucket in RelativeBucket must be reachable from a preset — the wide " +
                "view holds one section per bucket, so a bucket no preset uses is a " +
                "bucket nobody can reach",
        )
    }

    @Test
    fun `the bottom bar tabs leave a next-month task out`() {
        val task = task("next-month", LocalDate(2026, 11, 20))
        val tabs =
            listOf(
                "Inbox" to AgendaPresets.Inbox,
                "Today" to AgendaPresets.Today,
                "Upcoming" to AgendaPresets.Upcoming,
            )

        tabs.forEach { (name, definition) ->
            assertEquals(
                emptyList(),
                sectionsContaining(definition, task, midMonth),
                "the $name tab does not reach the next month — the bottom bar's horizon " +
                    "is next week, and the wide view reaches further than the bar does",
            )
        }
    }
}
