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
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.TaskValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the four properties that make the confirm path safe. Each corresponds to a
 * way the obvious implementation loses user data.
 */
@org.junit.jupiter.api.Tag("fast")
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
        projects = projects,
        tags = tags,
        planner = ProposalPlanner(clock),
        dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, projects, deleteProject, clock),
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
                targetKind = ProposalTargetKind.Task,
                targetId = target,
                userId = user,
                source = ProposalSource.Detail,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = listOf(item),
            ),
        )
        return item.id
    }

    /**
     * Seeds a one-item proposal whose change carries [status].
     *
     * A row written by a build that knew a status this one does not is the case
     * this is for: the change may already have been applied, and this build
     * cannot tell.
     */
    private suspend fun seedProposalWithStatus(
        kind: ProposalItemKind,
        status: ProposalItemStatus,
        target: String = taskId.value,
    ): ProposalItemId {
        val proposalId = ProposalId.generate()
        val item = ProposalItem(
            id = ProposalItemId.generate(),
            proposalId = proposalId,
            kind = kind,
            targetId = target,
            humanSummary = "summary",
            status = status,
            fingerprint = ProposalFingerprint.of(kind, target),
        )
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = ProposalTargetKind.Task,
                targetId = target,
                userId = user,
                source = ProposalSource.Detail,
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

        val id = seedProposal(ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)))

        assertTrue(useCase.confirm(id, user).isSuccess)

        assertEquals(TaskPriority.High, tasks.get(taskId)?.priority)
        // The field the proposal never mentioned must survive untouched.
        assertEquals("Edited by the user while the model was thinking", tasks.get(taskId)?.title)
    }

    // A value that does not read is refused before it is ever proposed — that case
    // lives with the operation vocabulary now (`ProposalOperationTest`), because the
    // reading happens where the proposal is built, not where it is applied.

    @Test
    fun `a blank proposed title is rejected before the claim`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskValue.Title("   ")))

        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals("Original", tasks.get(taskId)?.title)
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    @Test
    fun `a negative estimate is refused`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskValue.EstimateMinutes(-30)))

        assertTrue(useCase.confirm(id, user).isFailure)
        assertEquals(ProposalItemStatus.Pending, proposals.getItem(id)?.status)
    }

    @Test
    fun `an unknown task fails without claiming the item`() = runTest {
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)), target = "missing")

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

    // ── A decision that did not happen ───────────────────────────────────────

    @Test
    fun `a dispatch that fails leaves the item pending`() = runTest {
        tasks.seed(task())
        // The write fails at the dispatch, which is *after* the claim: by then the item
        // has been decided. The change was not applied, so the decision must not stand
        // on its own — the item goes back to pending and the user can try again.
        tasks.softDeleteOverride = Result.failure(IllegalStateException("the write failed"))
        val id = seedProposal(ProposalItemKind.DeleteTask(reason = null))

        assertTrue(useCase.confirm(id, user).isFailure)

        assertEquals(
            ProposalItemStatus.Pending,
            proposals.getItem(id)?.status,
            "the change was not applied, so it must not be recorded as decided",
        )
    }

    @Test
    fun `a change that was released can be applied again`() = runTest {
        tasks.seed(task())
        tasks.softDeleteOverride = Result.failure(IllegalStateException("the write failed"))
        val id = seedProposal(ProposalItemKind.DeleteTask(reason = null))

        assertTrue(useCase.confirm(id, user).isFailure)
        tasks.softDeleteOverride = null

        assertTrue(useCase.confirm(id, user).isSuccess, "the second attempt goes through")
        assertEquals(ProposalItemStatus.Confirmed, proposals.getItem(id)?.status)
    }

    @Test
    fun `a write that breaks halfway leaves nothing behind`() = runTest {
        tasks.seed(task())
        // The second row fails, the first was written. The change goes back to waiting,
        // so the user will try again — and the row that was written must not be there
        // a second time.
        checklist.addItemOverride = { title ->
            if (title == "second") Result.failure(IllegalStateException("the write failed")) else null
        }
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("first", "second")))

        assertTrue(useCase.confirm(id, user).isFailure)

        assertEquals(
            emptyList(),
            checklist.watchByTask(taskId.value).first().map { it.title },
            "the row that was written before the failure is taken back — otherwise the " +
                "next attempt would add it twice",
        )
    }

    @Test
    fun `a change that breaks halfway can be applied again`() = runTest {
        tasks.seed(task())
        checklist.addItemOverride = { title ->
            if (title == "second") Result.failure(IllegalStateException("the write failed")) else null
        }
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("first", "second")))

        assertTrue(useCase.confirm(id, user).isFailure)
        checklist.addItemOverride = null

        assertTrue(useCase.confirm(id, user).isSuccess, "the second attempt goes through")
        assertEquals(
            listOf("first", "second"),
            checklist.watchByTask(taskId.value).first().map { it.title }.sorted(),
            "each row is written once",
        )
    }

    @Test
    fun `a write that is cut short leaves nothing behind`() = runTest {
        tasks.seed(task())
        // The screen went away in the middle of the write. A cancelled coroutine is
        // not a failed one: the rows that were written before the cancellation stay
        // written, and the change goes back to waiting — so the row that stayed
        // would be there twice on the next attempt.
        checklist.addItemOverride = { title ->
            if (title == "second") throw CancellationException("the screen went away") else null
        }
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("first", "second")))

        assertFailsWith<CancellationException> { useCase.confirm(id, user) }

        assertEquals(
            emptyList(),
            checklist.watchByTask(taskId.value).first().map { it.title },
            "the row that was written before the cancellation is taken back — the change " +
                "waits again, and the row would be there twice after the next attempt",
        )
        assertEquals(
            ProposalItemStatus.Pending,
            proposals.getItem(id)?.status,
            "the change waits again — it was never decided",
        )
    }

    @Test
    fun `a row that was taken back is not taken back again`() = runTest {
        tasks.seed(task())
        // The write of the third row fails, and the take-back of the second row is
        // cut short — the driver notices that the scope is gone. The first row was
        // taken back already, and taking it back a second time would fail on a row
        // that is not there any more; and that failure would be reported as a row
        // that stayed, which is a row the user would be told about that is not there.
        checklist.addItemOverride = { title ->
            if (title == "third") Result.failure(IllegalStateException("the write failed")) else null
        }
        checklist.deleteOverride = {
            if (checklist.deleteCalls.size == 2) throw CancellationException("the screen went away")
        }
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("first", "second", "third")))

        assertFailsWith<CancellationException> { useCase.confirm(id, user) }

        assertEquals(
            checklist.deletedIds.distinct().size,
            checklist.deletedIds.size,
            "a row that was taken back is not taken back again: ${checklist.deletedIds}",
        )
        assertEquals(
            emptyList(),
            checklist.watchByTask(taskId.value).first().map { it.title },
            "no row is left behind — the row that stayed is taken back when the take-back is tried again",
        )
    }

    @Test
    fun `a row that was written and then lost is still taken back`() = runTest {
        tasks.seed(task())
        // The write committed and the screen went away before the write came back:
        // the row is there, and the id that would address it never arrived. That is
        // the moment the id has to be known already — before the write, not after
        // it. The change waits again, so a row that stayed would be there twice.
        checklist.afterWrite = { throw CancellationException("the screen went away") }
        val id = seedProposal(ProposalItemKind.AddChecklistItems(listOf("first")))

        assertFailsWith<CancellationException> { useCase.confirm(id, user) }

        assertEquals(
            emptyList(),
            checklist.watchByTask(taskId.value).first().map { it.title },
            "the row that was written is taken back — the id was known before the write",
        )
        assertEquals(
            ProposalItemStatus.Pending,
            proposals.getItem(id)?.status,
            "the change waits — it was never decided",
        )
    }

    @Test
    fun `a cancellation while the claim is in flight gives the claim back`() = runTest {
        tasks.seed(task())
        // The claim is a transaction, and what it decided is known inside it. A
        // cancellation that arrives while the claim is in flight must not leave the
        // claim standing over nothing: the claim's own call is not cancellable, so
        // the fact that it stands is known — and the work that follows it is
        // cancelled at its first suspension point, which is where the claim is
        // given back.
        proposals.afterClaim = { delay(50) }
        tasks.afterCreate = { delay(50) }
        val id = seedProposal(ProposalItemKind.AddSubtasks(listOf("Pick up milk")))

        val confirm = launch { useCase.confirm(id, user) }
        runCurrent()
        confirm.cancel()
        advanceUntilIdle()

        assertEquals(
            ProposalItemStatus.Pending,
            proposals.getItem(id)?.status,
            "the change waits — the claim was given back, because nothing was written",
        )
        assertTrue(
            tasks.observeAll().first().none { it.title == "Pick up milk" && it.archivedAt == null },
            "the subtask that was written is not there",
        )
    }

    @Test
    fun `a subtask that was written and then lost is archived, not left alive`() = runTest {
        tasks.seed(task())
        // The subtask was written and the write was lost. The take-back is a delete,
        // and a delete travels to the server as an archive: the other device does
        // not bring the row back as a live task. So the take-back goes through the
        // archive, not through a removal that only this device knows about.
        tasks.afterCreate = { throw CancellationException("the screen went away") }
        val id = seedProposal(ProposalItemKind.AddSubtasks(listOf("Pick up milk")))

        assertFailsWith<CancellationException> { useCase.confirm(id, user) }

        val written = tasks.observeAll().first().filter { it.title == "Pick up milk" }
        assertEquals(1, written.size, "the row that was written is still there")
        assertNotNull(
            written.single().archivedAt,
            "it is archived rather than deleted — the archive is what the sync carries",
        )
    }

    @Test
    fun `a change this build cannot read is refused with its reason`() = runTest {
        tasks.seed(task())
        // A row written by a build that knew a kind this one does not. The change
        // stays on the card with the reason, and applying it says why instead of
        // doing nothing.
        val id = seedProposal(ProposalItemKind.Unknown("""{"type":"ArchiveEverything"}"""))

        val result = useCase.confirm(id, user)

        assertTrue(result.isFailure, "a change that does not read cannot be applied")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("cannot read") == true,
            "the reason is the answer: ${result.exceptionOrNull()?.message}",
        )
        assertEquals(
            ProposalItemStatus.Pending,
            proposals.getItem(id)?.status,
            "the change waits — it was never decided",
        )
    }

    @Test
    fun `a value this build cannot read is refused with its reason`() = runTest {
        tasks.seed(task())
        // A row written by a build that knew the priority, read by one that does
        // not. The change stays on the card with the reason, and applying it says
        // why instead of doing nothing.
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskValue.Unreadable("Priority", "very high")))

        val result = useCase.confirm(id, user)

        assertTrue(result.isFailure, "a value that does not read cannot be applied")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("is not a priority") == true,
            "the reason is the answer: ${result.exceptionOrNull()?.message}",
        )
        assertEquals(
            ProposalItemStatus.Pending,
            proposals.getItem(id)?.status,
            "the change waits — it was never decided",
        )
    }

    @Test
    fun `a status this build cannot read is refused, and the change is not applied again`() = runTest {
        tasks.seed(task(title = "Original"))
        // A row written by a build that knew a status this one does not. The change
        // may already have been applied — so this build must not apply it a second
        // time, and it must say why instead of doing nothing quietly.
        val id = seedProposalWithStatus(
            kind = ProposalItemKind.SetTaskField(TaskValue.Title("Applied already")),
            status = ProposalItemStatus.Unknown("Applied"),
        )

        val result = useCase.confirm(id, user)

        assertTrue(result.isFailure, "a change whose status does not read cannot be applied")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Applied") == true,
            "the reason names the status this build does not know: ${result.exceptionOrNull()?.message}",
        )
        assertEquals(
            "Original",
            tasks.get(taskId)?.title,
            "the task is not written to — the change may already have been applied",
        )
    }

    @Test
    fun `a change whose status this build cannot read can still be turned down`() = runTest {
        // The user never said yes to it, so it is theirs to refuse — and a change
        // they cannot refuse is a card they cannot clear. The refusal records the
        // reason it could not be applied.
        val id = seedProposalWithStatus(
            kind = ProposalItemKind.SetTaskField(TaskValue.Title("Applied already")),
            status = ProposalItemStatus.Unknown("Applied"),
        )

        val result = useCase.reject(id, user, "I do not want this")

        assertTrue(result.isSuccess, "the change is turned down: $result")
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(id)?.status,
            "the change is refused, so the card can be cleared",
        )
    }

    @Test
    fun `the value the user typed while the change was in flight is the one applied`() = runTest {
        tasks.seed(task())
        val id = seedProposal(ProposalItemKind.SetTaskField(TaskValue.Title("Old title")))
        // The row changed between the read and the claim: the plan built from the old
        // row would write the old value over the new one, so the plan is rebuilt. The
        // cost is one more read of the task — and only when the row changed.
        proposals.beforeClaim = {
            proposals.updatePendingItem(
                id,
                ProposalItemKind.SetTaskField(TaskValue.Title("New title")),
                user,
            )
        }

        assertTrue(useCase.confirm(id, user).isSuccess)

        assertEquals(
            "New title",
            tasks.get(taskId)?.title,
            "the value the user typed is the one that was applied",
        )
    }

    @Test
    fun `a subtask that is taken back is archived, not deleted`() = runTest {
        tasks.seed(task())
        // The second subtask fails; the first was written. The take-back archives it:
        // production `delete` is a soft delete, so the row is still there and the
        // archive is what the sync carries. A hard delete would take the row out of
        // the sync — and a rolled-back subtask is not history the user made, it is a
        // row the app made and undid.
        tasks.createCallOverride = { title ->
            if (title == "second") Result.failure(IllegalStateException("the write failed")) else null
        }
        val id = seedProposal(ProposalItemKind.AddSubtasks(listOf("first", "second")))

        assertTrue(useCase.confirm(id, user).isFailure)

        val created = tasks.tasks.value.values.filter { it.title == "first" }
        assertEquals(1, created.size, "the subtask that was written is still there")
        assertNotNull(
            created.single().archivedAt,
            "it is archived rather than deleted — the archive is what the sync carries",
        )
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
                targetKind = ProposalTargetKind.Task,
                targetId = taskId.value,
                userId = user,
                source = ProposalSource.Card,
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
            // A title the task cannot hold: the plan fails, so this one item fails on
            // its own while the other still goes through.
            kind = ProposalItemKind.SetTaskField(TaskValue.Title("   ")),
            targetId = taskId.value,
            humanSummary = "bad",
            status = ProposalItemStatus.Pending,
            fingerprint = "fp-bad",
        )
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = ProposalTargetKind.Task,
                targetId = taskId.value,
                userId = user,
                source = ProposalSource.Card,
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
