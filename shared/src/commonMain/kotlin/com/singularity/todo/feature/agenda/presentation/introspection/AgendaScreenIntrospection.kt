package com.singularity.todo.feature.agenda.presentation.introspection

import com.singularity.todo.core.llm.ScreenAction
import com.singularity.todo.core.llm.ScreenIntrospection
import com.singularity.todo.core.llm.ScreenState
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.presentation.state.AgendaUiState
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The task-list adapter of the reusable [ScreenIntrospection] contract.
 *
 * The same screen with a different list behind it — the Inbox tab or a project's tasks — is one
 * screen id with a different [projectId], because the difference is a parameter and not a
 * different screen. The list itself is the real [AgendaViewModel]: an accepted action ends at
 * its `onIntent`, the same entry point a row's tap uses.
 *
 * ## Why this adapter exists
 *
 * Task Detail proved the contract against a coordinator whose subject is one task. This is the
 * second data point the rollout decision needs: a plain `MviViewModel` over a list, where the
 * subject is many rows, the focus is a selection, and a move to another screen is a one-shot
 * event rather than a state value. Nothing here re-derives the contract's semantics — the
 * allow-list, the schema check and validate-before-dispatch come from [ScreenIntrospection].
 */
class AgendaScreenIntrospection(
    private val viewModel: AgendaViewModel,
    private val projectId: ProjectId? = null,
    private val screenId: String = SCREEN_ID,
) {
    private val listActions: List<ScreenAction> = agendaActions()

    private val introspection = ScreenIntrospection(screenId = screenId, actions = listActions)

    /**
     * Where the screen asked to go, or null when it asked for nothing.
     *
     * Set by [onEvent] from the screen's own `NavigateToTask` event — the destination a row's
     * tap would hand the application's navigator. Published in the snapshot so a driver opens
     * the next screen instead of mutating navigation state itself, and cleared by
     * [clearNavigation] once the driver has acted on it.
     *
     * **Only task-opening is published.** The agenda also emits `NavigateToOverdue`,
     * `CreateInSection` and `OpenCreateWithDates`; those are deliberately not mapped. Each
     * names a destination this adapter has not been asked to describe, and inventing routes
     * for them would be guessing at the application's navigation rather than reporting it.
     * A driver sees `navigation: null` for those transitions and must treat that as "the
     * screen moved somewhere this contract does not cover", not as "nothing happened".
     */
    @Volatile
    var navigation: JsonObject? = null
        private set

    val actions: List<ScreenAction> = listActions

    fun describe(): JsonObject = introspection.describe(stateOf(viewModel.stateFlow.value))

    fun describe(state: AgendaUiState): JsonObject = introspection.describe(stateOf(state))

    fun snapshot(): JsonObject = introspection.snapshot(stateOf(viewModel.stateFlow.value))

    /**
     * The screen's state as this adapter publishes it — the same value [describe] and
     * [snapshot] build from, and the only thing [actions] availability is a function of.
     *
     * Exposed because a driver's question "can I act on this?" is answered about *this*, not
     * about the ViewModel's internal state: the two can disagree in principle, and a test that
     * asked the ViewModel could pass while the published availability was wrong.
     */
    fun publishedState(): ScreenState = stateOf(viewModel.stateFlow.value)

    fun snapshot(state: AgendaUiState): JsonObject = introspection.snapshot(stateOf(state))

    /** Records what the screen decided to do next. Only navigation is published from events. */
    fun onEvent(event: AgendaUiEvent) {
        if (event is AgendaUiEvent.NavigateToTask) {
            navigation =
                buildJsonObject {
                    put("screen", TASK_DETAIL_SCREEN_ID)
                    put("params", buildJsonObject { put(ACTION_TASK_ID, event.taskId.value) })
                }
        }
    }

    /** The driver took the pending navigation. Nothing is left on offer. */
    fun clearNavigation() {
        navigation = null
    }

    /**
     * Validates the action and hands it to the screen's own intent entry point.
     *
     * Two refusals happen before anything is dispatched. The contract's own check runs first
     * (unknown action, unavailable action, input outside the published schema). Then a task id
     * the published list does not show is refused: the row the agent named is not on the
     * screen, and dispatching against it would report an action the user could not have taken.
     */
    fun dispatch(
        action: String,
        input: JsonObject = JsonObject(emptyMap()),
    ): Result<Unit> {
        val state = viewModel.stateFlow.value
        val validated = introspection.validate(stateOf(state), action, input)
        if (validated.isFailure) return validated
        // The published schema rejects a blank id, so this cannot normally fire. It is here
        // because dispatch promises a Result<Unit>, and a refusal that escapes as a thrown
        // exception would be a second, undocumented error channel the moment the two ever
        // disagree.
        val taskId =
            taskIdOf(input)
                ?: return Result.failure(
                    IllegalArgumentException("'$ACTION_TASK_ID' is required and must be a non-empty string"),
                )
        val listed = stateOf(state).subject?.taskIds().orEmpty()
        if (taskId !in listed) {
            return Result.failure(
                IllegalArgumentException(
                    "Action '$action' names task '$taskId', which this list does not show. " +
                        "Listed task ids: ${listed.sorted().joinToString()}",
                ),
            )
        }
        viewModel.onIntent(intentFor(action, TaskId(taskId)))
        return Result.success(Unit)
    }

    fun stateOf(state: AgendaUiState): ScreenState =
        when (state) {
            AgendaUiState.Loading -> {
                ScreenState(status = STATUS_LOADING)
            }

            is AgendaUiState.Error -> {
                ScreenState(status = STATUS_ERROR, message = state.message)
            }

            is AgendaUiState.Loaded -> {
                ScreenState(
                    status = STATUS_LOADED,
                    subject = state.listSnapshot(),
                    focus =
                        if (state.isSelectionMode) {
                            buildJsonObject {
                                put(FOCUS_SELECTION, JsonArray(state.selectedTaskIds.map { JsonPrimitive(it.value) }))
                            }
                        } else {
                            null
                        },
                    navigation = navigation,
                )
            }
        }

    private fun AgendaUiState.Loaded.listSnapshot(): JsonObject =
        buildJsonObject {
            put("view", if (projectId == null) VIEW_INBOX else VIEW_PROJECT)
            put("today", today.toString())
            projectId?.let { put("projectId", it.value) }
            put(
                "sections",
                JsonArray(
                    sections.map { section ->
                        buildJsonObject {
                            put("id", section.id)
                            put("name", section.name)
                            section.badge?.let { put("badge", it) }
                            put(
                                "tasks",
                                JsonArray(
                                    section.tasks.map { row ->
                                        buildJsonObject {
                                            put("id", row.task.id.value)
                                            put("title", row.task.title)
                                            put("completed", row.task.completedAt != null)
                                            put("priority", row.task.priority.wireName)
                                            put("pinned", row.task.isPinned)
                                            row.task.dueDate?.let { put("dueDate", it.toString()) }
                                            row.project?.let { put("project", it) }
                                        }
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
        }

    private fun agendaActions(): List<ScreenAction> =
        listOf(
            ScreenAction(
                name = ACTION_OPEN_TASK,
                summary = "Open one of the tasks the list shows.",
                requirement = REQUIREMENT_NOT_SELECTING,
                inputSchema = schema(),
                // In selection mode the screen's own click handler toggles the selection
                // instead of navigating, so "open this task" is not a thing the screen can
                // do right now — and reporting the dispatch as accepted would be a lie.
                isAvailable = { state -> state.status == STATUS_LOADED && !state.isSelecting() },
            ),
            ScreenAction(
                name = ACTION_TOGGLE_COMPLETE,
                summary = "Mark one of the listed tasks done, or a done one not done.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(),
                // The check handler has no selection-mode branch: it completes the task
                // whatever the mode, so this action stays available while selecting.
                isAvailable = { state -> state.status == STATUS_LOADED },
            ),
            ScreenAction(
                name = ACTION_SELECT_TASK,
                summary = "Start multi-selection with one of the listed tasks.",
                requirement = REQUIREMENT_NOT_SELECTING,
                inputSchema = schema(),
                // The long-press handler returns early when the mode is already on, so a
                // dispatch here would be accepted and do nothing.
                isAvailable = { state -> state.status == STATUS_LOADED && !state.isSelecting() },
            ),
        )

    /** Whether the published state says the screen is in multi-selection mode. */
    private fun ScreenState.isSelecting(): Boolean = focus?.containsKey(FOCUS_SELECTION) == true

    private fun intentFor(
        action: String,
        taskId: TaskId,
    ): AgendaIntent =
        when (action) {
            ACTION_OPEN_TASK -> AgendaIntent.TaskClicked(taskId)
            ACTION_TOGGLE_COMPLETE -> AgendaIntent.TaskCheckClicked(taskId)
            ACTION_SELECT_TASK -> AgendaIntent.TaskLongClicked(taskId)
            else -> throw IllegalArgumentException("Unknown action '$action'.")
        }

    private fun JsonObject.taskIds(): Set<String> {
        val sections = this["sections"] as? JsonArray ?: return emptySet()
        return sections
            .flatMap { section -> ((section as? JsonObject)?.get("tasks") as? JsonArray).orEmpty() }
            .mapNotNull { row -> (row as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            .toSet()
    }

    private fun schema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("required", JsonArray(listOf(JsonPrimitive(ACTION_TASK_ID))))
            put(
                "properties",
                buildJsonObject {
                    put(
                        ACTION_TASK_ID,
                        buildJsonObject {
                            put("type", "string")
                            put("minLength", 1)
                            // Same statement the dispatcher makes below: a blank id is not an
                            // id. Without it the schema accepts `" "` and the refusal arrives
                            // after validation, which is not what "the schema says what the
                            // dispatcher enforces" promises an agent.
                            put("pattern", "\\S")
                        },
                    )
                },
            )
            put("additionalProperties", false)
        }

    private fun taskIdOf(input: JsonObject): String? =
        (input[ACTION_TASK_ID] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }

    private companion object {
        /** The stable name of the screen this contract describes. */
        const val SCREEN_ID = "agenda.tasks"

        /** Where a row's tap goes. The list does not describe the next screen; it names it. */
        const val TASK_DETAIL_SCREEN_ID = "tasks.task-detail"

        const val STATUS_LOADING = "loading"
        const val STATUS_ERROR = "error"
        const val STATUS_LOADED = "loaded"

        const val VIEW_INBOX = "inbox"
        const val VIEW_PROJECT = "project"

        const val REQUIREMENT_LOADED = "the list has to be showing its tasks"
        const val REQUIREMENT_NOT_SELECTING = "the list must not be in multi-selection mode"

        /** The focus key that says the screen is selecting; the same key the snapshot publishes. */
        const val FOCUS_SELECTION = "selection"

        const val ACTION_OPEN_TASK = "open_task"
        const val ACTION_TOGGLE_COMPLETE = "toggle_complete"
        const val ACTION_SELECT_TASK = "select_task"

        const val ACTION_TASK_ID = "taskId"
    }
}
