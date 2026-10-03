@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.proposals

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.TaskField
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the four properties that make the confirm path safe. Each corresponds to a
 * way the obvious implementation loses user data.
 */
class ApplyProposalItemUseCaseTest {

    private val clock = FakeClock()

    // The fakes scope every read to their own current user, which defaults to
    // TestUsers.DEFAULT. Fixtures must be stamped with the same id or they seed
    // successfully and are then invisible to the code under test.
    private val user = TestUsers.DEFAULT
    private val taskId = TaskId("t1")

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository()
    private val tags = FakeTagsRepository()
    private val checklist = FakeChecklistRepository()
    private val timeTracking = FakeTimeTrackingRepository(clock)
    private val notes = FakeNotesRepository()
    private val projects: ProjectsRepository = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    private val deleteProject = DeleteProjectUseCase(projects, tasks)

    private val useCase = ApplyProposalItemUseCase(
        proposals = proposals,
        tasks = tasks,
        notes = notes,
        tags = tags,
        planner = ProposalPlanner(clock),
        dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, deleteProject, clock),
    )

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun tag(id: String, name: String) = Tag(
        id = TagId(id),
        name = name,
        color = 0,
        userId = user,
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    private fun task(id: TaskId = taskId, title: String = "Original", priority: TaskPriority = TaskPriority.None) =
        Task(
            id = id,
            title = title,
            userId = user,
            priority = priority,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    /** Seeds a one-item proposal for [kind] and returns the item id. */
    private suspend fun seedProposal(kind: ProposalItemKind, target: String = taskId.value): ProposalItemId {
        val proposalId = ProposalId.generate()
        val item = ProposalItem(
            id = ProposalItemId.generate(),
            proposalId = proposalId,
            kind = kind,
            targetId = target,
            humanSummary = "summary",
            status = ProposalItemStatus.Pending,
            fingerprint = ProposalFingerprint.of(kind, target),
        )
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = AiProposal.TARGET_KIND_TASK,
                targetId = target,
                userId = user,
                source = ProposalSource.Detail,
                status = ProposalStatus.Pending,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = listOf(item),
            ),
        )
        return item.id
    }

    // ── Read before write ─────────────────────────────────────────────────────

    @Test
    fun `field is written onto the current task, not the value the agent saw`() = runTest {
        tasks.seed(task(title = "Edited by the user while the model was thinking"))

        val id = seedProposal(ProposalItemKind.SetTaskField(TaskField.Priority, "High"))

        assertTrue(useCase.confirm(id, user).isSuccess)

        assertEquals(TaskPriority.High, tasks.get(taskId)?.priority)
        // The field the proposal never mentioned must survive untouched.
        assertEquals("Edited by the user while the model was thinking", tasks.get(taskId)?.title)
    }

    @Test
    fun `an unparseable value leaves the item pending and the task untouched`() = runTest {
        tasks.seed(task(priority = TaskPriority.Low))
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskField.Priority, "VERY IMPORTANT"))

        val result = useCase.confirm(id, user)

        assertTrue(result.isFailure)
        assertEquals(TaskPriority.Low, tasks.get(taskId)?.priority, "task must not change")
        // The whole point of planning before claiming: a re-try is still possible.
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    @Test
    fun `a blank proposed title is rejected before the claim`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskField.Title, "   "))

        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals("Original", tasks.get(taskId)?.title)
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    @Test
    fun `a negative estimate is refused`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskField.EstimateMinutes, "-30"))

        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    @Test
    fun `an unknown task fails without claiming the item`() = runTest {
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskField.Priority, "High"), target = "missing")

        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    // ── Exactly once ──────────────────────────────────────────────────────────

    @Test
    fun `a second confirm applies nothing`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("buy milk")))

        assertTrue(useCase.confirm(id, user).isSuccess)
        val afterFirst = checklist.items.value.size

        // The double-tap that a compare-and-set exists to absorb.
        val second = useCase.confirm(id, user)

        assertTrue(second.isFailure, "an already-decided item must not be confirmable again")
        assertEquals(afterFirst, checklist.items.value.size, "no second checklist item may be added")
    }

    @Test
    fun `a confirm after a reject applies nothing`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("buy milk")))

        assertTrue(useCase.reject(id, user, "not my job").isSuccess)
        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals(0, checklist.items.value.size)
    }

    @Test
    fun `confirm then reject leaves the confirmed change in place`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("buy milk")))

        useCase.confirm(id, user)
        useCase.reject(id, user, "changed my mind")

        assertEquals(ProposalItemStatus.Confirmed, proposals.getItem(id)?.status)
        assertEquals(1, checklist.items.value.size)
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Test
    fun `add tags resolves names to ids and keeps existing ones`() = runTest {
        tasks.seed(task())
        tags.seed(
            tag("tag-home", "Home"),
            tag("tag-old", "Old"),
        )
        tasks.setTags(taskId, listOf(TagId("tag-old")))

        val id = seedProposal(ProposalItemKind.AddTags(listOf("Home", "Nonexistent")))
        assertTrue(useCase.confirm(id, user).isSuccess)

        val ids = tasks.getTagIds(taskId).valueOrNull().orEmpty().map { it.value }.toSet()
        assertEquals(setOf("tag-old", "tag-home"), ids)
    }

    @Test
    fun `remove tags drops only the named ids`() = runTest {
        tasks.seed(task())
        tasks.setTags(
            taskId,
            listOf(
                TagId("tag-a"),
                TagId("tag-b"),
            ),
        )

        val id = seedProposal(ProposalItemKind.RemoveTags(listOf("tag-a")))
        assertTrue(useCase.confirm(id, user).isSuccess)

        val ids = tasks.getTagIds(taskId).valueOrNull().orEmpty().map { it.value }.toSet()
        assertEquals(setOf("tag-b"), ids)
    }

    @Test
    fun `add subtasks creates children of the owning task`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.AddSubtasks(listOf("step one", "step two")))

        assertTrue(useCase.confirm(id, user).isSuccess)

        val children = tasks.observeSubtasks(taskId).valueOrNull().orEmpty()
        assertEquals(setOf("step one", "step two"), children.map { it.title }.toSet())
        assertTrue(children.all { it.parentTaskId == taskId })
    }

    @Test
    fun `add time entries logs them against the owning user`() = runTest {
        tasks.seed(task())
        val id = seedProposal(
            ProposalItemKind.AddTimeEntries(listOf(ProposedTimeEntry(startedAt = 1000, endedAt = 2000))),
        )

        assertTrue(useCase.confirm(id, user).isSuccess)

        val entries = timeTracking.watchEntries(taskId).valueOrNull().orEmpty()
        assertEquals(1, entries.size)
        assertEquals(user, entries.single().userId)
        assertEquals(1000L, entries.single().startedAt.toEpochMilliseconds())
    }

    @Test
    fun `a time entry that ends before it starts is refused`() = runTest {
        tasks.seed(task())
        val id = seedProposal(
            ProposalItemKind.AddTimeEntries(listOf(ProposedTimeEntry(startedAt = 2000, endedAt = 1000))),
        )

        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    @Test
    fun `an empty checklist batch is refused rather than silently doing nothing`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("  ", "")))

        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @Test
    fun `confirming every item resolves the proposal`() = runTest {
        tasks.seed(task())
        val proposalId = ProposalId.generate()
        val items = listOf("a", "b").map { text ->
            ProposalItem(
                id = ProposalItemId.generate(),
                proposalId = proposalId,
                kind = ProposalItemKind.AddChecklistItems(listOf(text)),
                targetId = taskId.value,
                humanSummary = text,
                status = ProposalItemStatus.Pending,
                fingerprint = ProposalFingerprint.of(
                    ProposalItemKind.AddChecklistItems(listOf(text)),
                    taskId.value,
                ),
            )
        }
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = AiProposal.TARGET_KIND_TASK,
                targetId = taskId.value,
                userId = user,
                source = ProposalSource.Card,
                status = ProposalStatus.Pending,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = items,
            ),
        )

        val result = useCase.confirmAll(proposalId, user)

        assertEquals(2, result.applied.size)
        assertEquals(emptyList(), result.failed)
        assertEquals(ProposalStatus.Resolved, proposals.watchProposal(proposalId).valueOrNull()?.status)
    }

    @Test
    fun `confirm all reports partial success instead of failing the batch`() = runTest {
        tasks.seed(task())
        val proposalId = ProposalId.generate()
        val good = ProposalItem(
            id = ProposalItemId.generate(),
            proposalId = proposalId,
            kind = ProposalItemKind.AddChecklistItems(listOf("fine")),
            targetId = taskId.value,
            humanSummary = "fine",
            status = ProposalItemStatus.Pending,
            fingerprint = "fp-good",
        )
        val bad = ProposalItem(
            id = ProposalItemId.generate(),
            proposalId = proposalId,
            kind = ProposalItemKind.SetTaskField(TaskField.Priority, "NOPE"),
            targetId = taskId.value,
            humanSummary = "bad",
            status = ProposalItemStatus.Pending,
            fingerprint = "fp-bad",
        )
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = AiProposal.TARGET_KIND_TASK,
                targetId = taskId.value,
                userId = user,
                source = ProposalSource.Card,
                status = ProposalStatus.Pending,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = listOf(good, bad),
            ),
        )

        val result = useCase.confirmAll(proposalId, user)

        assertEquals(1, result.applied.size, "the applicable item must still be applied")
        assertEquals(1, result.failed.size)
        assertEquals(ProposalStatus.Pending, proposals.watchProposal(proposalId).valueOrNull()?.status)
    }

    // ── Rejection feedback ────────────────────────────────────────────────────

    @Test
    fun `a rejection reason is stored and becomes a suppression fingerprint`() = runTest {
        val kind = ProposalItemKind.AddTags(listOf("home"))
        val id = seedProposal(kind)

        useCase.reject(id, user, "  I use a different tag for this  ")

        val item = assertNotNull(proposals.getItem(id))
        assertEquals(ProposalItemStatus.Rejected, item.status)
        assertEquals("I use a different tag for this", item.rejectionReason)
        assertTrue(proposals.rejectedFingerprints(user).contains(item.fingerprint))
    }

    @Test
    fun `a blank rejection reason is stored as null`() = runTest {
        val id = seedProposal(ProposalItemKind.AddTags(listOf("home")))

        useCase.reject(id, user, "   ")

        assertEquals(null, proposals.getItem(id)?.rejectionReason)
    }

    @Test
    fun `rejected fingerprints are listed newest first and bounded`() = runTest {
        val first = seedProposal(ProposalItemKind.AddTags(listOf("a")))
        useCase.reject(first, user, "no")
        // The clock must move between the two rejections: ordering is by decided_at,
        // and two rows sharing a timestamp have no defined order.
        clock.advance(1.seconds)
        val second = seedProposal(ProposalItemKind.AddTags(listOf("b")))
        useCase.reject(second, user, "also no")

        val fingerprints = proposals.rejectedFingerprints(user, limit = 1)
        assertEquals(1, fingerprints.size)
        assertEquals(proposals.getItem(second)?.fingerprint, fingerprints.single())
    }

    @Test
    fun `another user's rejections do not suppress this user`() = runTest {
        val id = seedProposal(ProposalItemKind.AddTags(listOf("home")))
        useCase.reject(id, user, "no")

        assertTrue(proposals.rejectedFingerprints(UserId("someone-else")).isEmpty())
    }
}

/**
 * Current value of a flow, or null if it has none.
 *
 * These tests assert against fake repositories, whose flows are backed by
 * `MutableStateFlow` and therefore always have a current value — so this is a
 * convenience, not a general-purpose replacement for collecting.
 */
private suspend fun <T> Flow<T>.valueOrNull(): T? = first()
