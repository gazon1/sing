package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.TaskField
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.presentation.state.TaskAiIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI-assisted actions on the task: refine the title, generate a description, generate a
 * checklist, decompose into subtasks, suggest a time.
 *
 * All five actions create [AiProposal] items rather than writing directly.
 * The user confirms or rejects each item in the [AiProposal]; only confirmed items
 * are applied to the task. This mirrors the design in the 2026-10-02 ADR.
 *
 * One intent and five actions, because they share a shape — call a use case, build a
 * proposal, save it. Five separate intents would repeat that shape five times.
 *
 * The use cases and proposal repository are nullable in [TaskDetailDeps] so tests can
 * omit them. A missing use case or repository takes the same failure path as a failed
 * call, so a misconfigured build reports instead of silently doing nothing.
 *
 * [TaskAiState.isRunning] is published so the UI can disable its trigger while a request
 * is in flight.
 */
class TaskAiSlot(
    private val deps: TaskDetailDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
    private val onSaved: (String) -> Unit,
) : FeatureSlot<TaskAiState, TaskAiIntent> {

    private val _state = MutableStateFlow(TaskAiState())
    override val state: StateFlow<TaskAiState> = _state.asStateFlow()

    override fun onIntent(intent: TaskAiIntent) {
        when (intent) {
            is TaskDetailIntent.Domain.RunAiAction -> run(intent.action)
        }
    }

    private fun run(action: TaskAiAction) = scope.launch {
        val task = taskFlow.value ?: return@launch
        _state.update { it.copy(isRunning = true) }
        val result = runCatchingCancellable { execute(action, task) }
        _state.update { it.copy(isRunning = false) }
        result.onFailure { onError("AI action failed: ${it.message}") }
    }

    private suspend fun execute(action: TaskAiAction, task: Task) {
        val proposals = deps.proposals
            ?: error("ProposalRepository not configured")
        val userId = deps.currentUser.scopedUserId.value
        val now = deps.clock.now()
        val proposalId = ProposalId.generate()

        val items = buildProposalItems(action, task, proposalId)
        val proposal = AiProposal(
            id = proposalId,
            taskId = task.id,
            userId = userId,
            source = ProposalSource.Detail,
            status = ProposalStatus.Pending,
            createdAt = now,
            updatedAt = now,
            items = items,
        )
        proposals.save(proposal).getOrThrow()
        onSaved("Proposal created")
    }

    private suspend fun buildProposalItems(
        action: TaskAiAction,
        task: Task,
        proposalId: ProposalId,
    ): List<ProposalItem> = when (action) {
        TaskAiAction.RefineTitle -> listOf(
            newItem(
                kind = ProposalItemKind.SetTaskField(
                    field = TaskField.Title,
                    value = withUseCase(deps.refineTask, "RefineTitle") { refine ->
                        refine(task.title, task.description).getOrThrow()
                    },
                ),
                proposalId = proposalId,
                targetId = task.id.value,
                summary = "Refine title",
            ),
        )

        TaskAiAction.GenerateDescription -> listOf(
            newItem(
                kind = ProposalItemKind.SetTaskField(
                    field = TaskField.Description,
                    value = withUseCase(deps.generateDescription, "GenerateDescription") { generate ->
                        generate(task.title).getOrThrow()
                    },
                ),
                proposalId = proposalId,
                targetId = task.id.value,
                summary = "Generate description",
            ),
        )

        TaskAiAction.GenerateChecklist -> {
            val steps = withUseCase(deps.generateChecklist, "GenerateChecklist") { generate ->
                generate(task.title, task.description).getOrThrow()
            }
            listOf(
                newItem(
                    kind = ProposalItemKind.AddChecklistItems(steps),
                    proposalId = proposalId,
                    targetId = task.id.value,
                    summary = "${steps.size} checklist items",
                ),
            )
        }

        TaskAiAction.Decompose -> {
            val subtasks = withUseCase(deps.decomposeTask, "DecomposeTask") { decompose ->
                decompose(task.title, task.description).getOrThrow()
            }
            listOf(
                newItem(
                    kind = ProposalItemKind.AddSubtasks(subtasks),
                    proposalId = proposalId,
                    targetId = task.id.value,
                    summary = "${subtasks.size} subtasks",
                ),
            )
        }

        TaskAiAction.SuggestTime -> {
            val suggestion = withUseCase(deps.pickTime, "PickTime") { pick ->
                pick(task.title, task.description).getOrThrow()
            }
            listOf(
                newItem(
                    kind = ProposalItemKind.AddTimeEntries(
                        listOf(ProposedTimeEntry(startedAt = 0L, endedAt = 0L, note = suggestion)),
                    ),
                    proposalId = proposalId,
                    targetId = task.id.value,
                    summary = "Suggest time: $suggestion",
                ),
            )
        }
    }

    private fun newItem(
        kind: ProposalItemKind,
        proposalId: ProposalId,
        targetId: String,
        summary: String,
    ): ProposalItem {
        val id = ProposalItemId.generate()
        return ProposalItem(
            id = id,
            proposalId = proposalId,
            kind = kind,
            targetId = targetId,
            humanSummary = summary,
            status = ProposalItemStatus.Pending,
            fingerprint = ProposalFingerprint.of(kind, targetId),
            sortOrder = 0,
        )
    }

    /**
     * Runs [block] with the use case, or fails when it is not configured.
     *
     * The AI use cases are nullable in [TaskDetailDeps] so tests can omit them; this turns
     * "not configured" into the same failure path as a failed call, so a misconfigured
     * build reports instead of silently doing nothing.
     */
    private suspend fun <T, R> withUseCase(useCase: T?, label: String, block: suspend (T) -> R): R {
        if (useCase == null) throw IllegalStateException("$label use case not available")
        return block(useCase)
    }
}
