package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
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
 * One intent and five actions, because they share a shape — call a use case, apply the
 * result to the task or its children, report. Five separate intents would repeat that
 * shape five times in the coordinator's `when`.
 *
 * The use cases are nullable in [TaskDetailDeps] so tests can omit them. A missing use
 * case takes the same failure path as a failed call, so a misconfigured build reports
 * instead of silently doing nothing.
 *
 * [TaskAiState.isRunning] is published so the UI can disable its trigger while a request
 * is in flight; the previous implementation tracked the same flag but never surfaced it.
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
            else -> Unit // unreachable through the coordinator's exhaustive `when`
        }
    }

    private fun run(action: TaskAiAction) = scope.launch {
        val task = taskFlow.value ?: return@launch
        _state.update { it.copy(isRunning = true) }
        val result = runCatching { execute(action, task) }
        _state.update { it.copy(isRunning = false) }
        result.onFailure { onError("AI action failed: ${it.message}") }
    }

    private suspend fun execute(action: TaskAiAction, task: Task) {
        when (action) {
            TaskAiAction.RefineTitle -> withUseCase(deps.refineTask, "RefineTitle") { refine ->
                val title = refine(task.title, task.description).getOrThrow()
                deps.updateTask(task.copy(title = title))
                    .onSuccess { onSaved("Title refined") }
            }

            TaskAiAction.GenerateDescription ->
                withUseCase(deps.generateDescription, "GenerateDescription") { generate ->
                    val description = generate(task.title).getOrThrow()
                    deps.updateTask(task.copy(description = description))
                        .onSuccess { onSaved("Description generated") }
                }

            TaskAiAction.GenerateChecklist ->
                withUseCase(deps.generateChecklist, "GenerateChecklist") { generate ->
                    val steps = generate(task.title, task.description).getOrThrow()
                    steps.forEach { step ->
                        deps.createTask(CreateTaskInput(title = step, parentTaskId = task.id))
                    }
                    onSaved("${steps.size} checklist items added")
                }

            TaskAiAction.Decompose -> withUseCase(deps.decomposeTask, "DecomposeTask") { decompose ->
                val subtasks = decompose(task.title, task.description).getOrThrow()
                subtasks.forEach { title ->
                    deps.createTask(CreateTaskInput(title = title, parentTaskId = task.id))
                }
                onSaved("${subtasks.size} subtasks created")
            }

            TaskAiAction.SuggestTime -> withUseCase(deps.pickTime, "PickTime") { pick ->
                onSaved("Suggested: ${pick(task.title, task.description).getOrThrow()}")
            }
        }
    }

    /**
     * Runs [block] with the use case, or fails when it is not configured.
     *
     * The AI use cases are nullable in [TaskDetailDeps] so tests can omit them; this turns
     * "not configured" into the same failure path as a failed call, so a misconfigured
     * build reports instead of silently doing nothing.
     */
    private suspend fun <T> withUseCase(useCase: T?, label: String, block: suspend (T) -> Unit) {
        if (useCase == null) throw IllegalStateException("$label use case not available")
        block(useCase)
    }
}
