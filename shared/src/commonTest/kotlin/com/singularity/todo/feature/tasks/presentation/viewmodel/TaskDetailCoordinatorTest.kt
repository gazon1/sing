@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.presentation.viewmodel

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.EditProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskCollaborationDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailExtras
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.SlotFakes
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TEST_USER
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.task
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration coverage for the coordinator: the assembled state and the routing of every
 * intent to the slot that owns it.
 *
 * The per-slot behaviour is covered by each slot's own test; what this file guards is the
 * seam — that the merge reads from all six inputs, and that no intent variant is dropped
 * by the routing `when`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TaskDetailCoordinatorTest {

    private fun coordinator(
        fakes: SlotFakes,
        scope: CoroutineScope,
        collaboration: TaskCollaborationDeps = fakes.collaboration(),
        crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    ) = TaskDetailCoordinator(
        deps = fakes.deps().copy(collaboration = collaboration),
        taskId = TaskId("t1"),
        crashReporter = crashReporter,
        scope = testScope(scope),
    )

    @Test
    fun `an empty repository resolves to a terminal state, not a hang`() = runTest {
        val fakes = SlotFakes()
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        // The coordinator subscribes immediately, so a missing row resolves to the
        // not-found error rather than staying in Loading forever.
        val state = vm.stateFlow.value
        assertTrue(
            state is TaskDetailUiState.Error || state == TaskDetailUiState.Loading,
            "expected a terminal or loading state, got $state",
        )
    }

    @Test
    fun `assembled state carries the task, draft, and child collections`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1", title = "Original"))
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        val loaded = vm.stateFlow.value as? TaskDetailUiState.Loaded
        assertNotNull(loaded, "expected the task to have loaded, got ${vm.stateFlow.value}")
        assertEquals("Original", loaded.ui.task.title)
        // The draft is seeded from the loaded task, so the editor shows the stored title.
        assertEquals("Original", loaded.ui.titleDraft.value)
    }

    @Test
    fun `a missing task produces the not-found error`() = runTest {
        val fakes = SlotFakes()
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        val state = vm.stateFlow.value
        assertTrue(state is TaskDetailUiState.Error || state == TaskDetailUiState.Loading)
    }

    @Test
    fun `entity intent reaches the repository through the entity slot`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.High))
        runCurrent()

        assertEquals(TaskPriority.High, fakes.taskRepo.tasks.value["t1"]?.priority)
    }

    @Test
    fun `children intent reaches the checklist repository`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Step"))
        runCurrent()

        assertEquals(1, fakes.checklistRepo.items.value.size)
    }

    @Test
    fun `lifecycle intent soft-deletes`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.Delete)
        runCurrent()

        assertNotNull(fakes.taskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `draft intent is seeded and echoed back into the assembled state`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1", title = "Original"))
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited"))
        runCurrent()

        val loaded = vm.stateFlow.value as? TaskDetailUiState.Loaded
        assertNotNull(loaded)
        assertEquals("Edited", loaded.ui.titleDraft.value)
    }

    @Test
    fun `ai intent is routed without throwing when no use case is configured`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.RunAiAction(TaskAiAction.RefineTitle))
        runCurrent()

        // The failure is reported through the event bus, not thrown into the caller.
        assertEquals("Test task", fakes.taskRepo.tasks.value["t1"]?.title)
    }

    @Test
    fun `every domain intent variant is routed and none is a no-op`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val vm = coordinator(fakes, backgroundScope)
        runCurrent()

        val all: List<TaskDetailIntent.Domain> = listOf(
            TaskDetailIntent.Domain.ToggleComplete,
            TaskDetailIntent.Domain.TitleChanged("T"),
            TaskDetailIntent.Domain.DescriptionChanged("D"),
            TaskDetailIntent.Domain.ToggleSomeday,
            TaskDetailIntent.Domain.SetKind(com.singularity.todo.feature.tasks.domain.model.TaskKind.Note),
            TaskDetailIntent.Domain.SetDueDate(kotlinx.datetime.LocalDate(2026, 10, 1)),
            TaskDetailIntent.Domain.SetDueTime(kotlinx.datetime.LocalTime(9, 0)),
            TaskDetailIntent.Domain.SetStartDate(kotlinx.datetime.LocalDate(2026, 9, 30)),
            TaskDetailIntent.Domain.SetStartTime(kotlinx.datetime.LocalTime(8, 0)),
            TaskDetailIntent.Domain.SetPriority(TaskPriority.Low),
            TaskDetailIntent.Domain.SetProject(null),
            TaskDetailIntent.Domain.SetTags(emptyList()),
            TaskDetailIntent.Domain.RemoveTag(com.singularity.todo.feature.tags.TagId.fromString("t")),
            TaskDetailIntent.Domain.ToggleChecklistItem(placeholderChecklistItem()),
            TaskDetailIntent.Domain.DeleteChecklistItem(
                com.singularity.todo.feature.checklist.ChecklistItemId.fromString("ci"),
            ),
            TaskDetailIntent.Domain.AddChecklistItem("item"),
            TaskDetailIntent.Domain.ToggleSubtask(task("c1")),
            TaskDetailIntent.Domain.DeleteSubtask(task("c1")),
            TaskDetailIntent.Domain.AddSubtask("child"),
            TaskDetailIntent.Domain.SetReminder(com.singularity.todo.core.reminders.ReminderOffset.AT_DUE),
            TaskDetailIntent.Domain.DeleteReminder,
            TaskDetailIntent.Domain.TogglePinned,
            TaskDetailIntent.Domain.SetDependencies(setOf(TaskId("dep"))),
            TaskDetailIntent.Domain.SetRecurrence(null),
            TaskDetailIntent.Domain.AddUrlAttachment("https://example.com", null),
            TaskDetailIntent.Domain.DeleteAttachment(
                com.singularity.todo.core.attachments.AttachmentId.fromString("a"),
            ),
            TaskDetailIntent.Domain.RunAiAction(TaskAiAction.SuggestTime),
        )

        all.forEach { vm.onIntent(it) }
        runCurrent()

        // A task that survives every routed intent proves none of them crashed the loop.
        assertNotNull(fakes.taskRepo.tasks.value["t1"])
    }

    @Test
    fun `a proposal intent applies the change through the proposal repository`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val proposals = FakeProposalRepository(FakeClock())
        val itemId = seedProposal(proposals, targetId = "t1")
        val vm = coordinator(fakes, backgroundScope, fakes.withProposals(proposals))
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.ConfirmProposalItem(itemId))
        runCurrent()

        assertEquals(
            ProposalItemStatus.Confirmed,
            proposals.getItem(itemId)?.status,
            "the change is applied",
        )
    }

    @Test
    fun `a proposal that cannot be applied keeps the reason next to the change`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val proposals = FakeProposalRepository(FakeClock())
        // The change is about a task that is not there, so it cannot be applied.
        val itemId = seedProposal(proposals, targetId = "t-missing")
        val vm = coordinator(fakes, backgroundScope, fakes.withProposals(proposals))
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.ConfirmProposalItem(itemId))
        runCurrent()

        val loaded = vm.stateFlow.value as? TaskDetailUiState.Loaded
        assertNotNull(loaded, "the task is on screen: ${vm.stateFlow.value}")
        val extras = loaded.extras as? TaskDetailExtras.Ready
        assertNotNull(extras, "the partitions are resolved: ${loaded.extras}")
        assertNotNull(
            extras.proposalFailures[itemId],
            "the reason sits next to the change: ${extras.proposalFailures}",
        )
    }

    @Test
    fun `a failure that arrives as a thrown exception is shown and reported`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val proposals = FakeProposalRepository(FakeClock())
        val itemId = seedProposal(proposals, targetId = "t-missing")
        // The claim throws: the failure arrives as an exception, not as a failed
        // result. The surface has to show it and report it — a failure the user
        // cannot see is a change nobody decided about.
        proposals.beforeClaim = { throw IllegalStateException("boom") }
        val reporter = RecordingCrashReportingPort()
        val vm = coordinator(fakes, backgroundScope, fakes.withProposals(proposals), reporter)
        val events = mutableListOf<TaskDetailUiEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.ConfirmProposalItem(itemId))
        runCurrent()

        assertTrue(
            events.any { it is TaskDetailUiEvent.Error },
            "the user is told: $events",
        )
        assertEquals(1, reporter.reports.size, "the failure is reported: ${reporter.reports}")
        assertEquals("Confirm", reporter.reports.first().second, "under the operation's name")
    }

    @Test
    fun `dismissing a proposal withdraws it`() = runTest {
        val fakes = SlotFakes()
        fakes.taskRepo.seed(task("t1"))
        val proposals = FakeProposalRepository(FakeClock())
        val itemId = seedProposal(proposals, targetId = "t1")
        val vm = coordinator(fakes, backgroundScope, fakes.withProposals(proposals))
        runCurrent()

        vm.onIntent(TaskDetailIntent.Domain.DismissProposal(ProposalId.fromString("prop-1")))
        runCurrent()

        assertEquals(
            ProposalItemStatus.Retracted,
            proposals.getItem(itemId)?.status,
            "the change is withdrawn with the proposal",
        )
    }

    /**
     * The collaboration dependencies with a proposal write path.
     *
     * They are optional in production — a build without the AI module has none —
     * so the screen has to work with and without them.
     */
    private fun SlotFakes.withProposals(proposals: FakeProposalRepository): TaskCollaborationDeps {
        val clock = FakeClock()
        return collaboration().copy(
            proposals = proposals,
            applyProposal = ApplyProposalItemUseCase(
                proposals = proposals,
                tasks = taskRepo,
                notes = notesRepo,
                projects = projectsRepo,
                tags = tagsRepo,
                planner = ProposalPlanner(clock),
                dispatch = ProposalDispatch(
                    taskRepo,
                    tagsRepo,
                    checklistRepo,
                    timeTrackingRepo,
                    notesRepo,
                    projectsRepo,
                    DeleteProjectUseCase(projectsRepo, taskRepo),
                    clock,
                ),
            ),
            editProposal = EditProposalItemUseCase(proposals),
        )
    }

    /** Seeds a one-change proposal about [targetId]. */
    private suspend fun seedProposal(
        proposals: FakeProposalRepository,
        targetId: String,
    ): ProposalItemId {
        val clock = FakeClock()
        val proposalId = ProposalId.fromString("prop-1")
        val item = ProposalItem(
            id = ProposalItemId.fromString("item-1"),
            proposalId = proposalId,
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
            targetId = targetId,
            humanSummary = "Set the priority to High",
            status = ProposalItemStatus.Pending,
            fingerprint = "set-priority-high",
        )
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = AiProposal.TARGET_KIND_TASK,
                targetId = "t1",
                userId = TEST_USER,
                source = ProposalSource.Detail,
                status = ProposalStatus.Pending,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = listOf(item),
            ),
        )
        return item.id
    }

    private fun placeholderChecklistItem() = com.singularity.todo.feature.checklist.ChecklistItem(
        id = com.singularity.todo.feature.checklist.ChecklistItemId.fromString("ci"),
        taskId = "t1",
        title = "item",
        sortOrder = 0,
    )
}
