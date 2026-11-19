package com.singularity.todo.feature.tasks.presentation.introspection

import com.singularity.todo.core.llm.ScreenAction
import com.singularity.todo.core.llm.ScreenIntrospection
import com.singularity.todo.core.llm.ScreenState
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Task Detail adapter of the reusable [ScreenIntrospection] contract.
 *
 * The contract carries the semantics every agent-facing screen shares — the allow-listed
 * snapshot, the action catalogue with JSON-Schema inputs, validate-before-dispatch,
 * availability, and the accept-vs-completion dispatch distinction. This adapter binds them
 * to the Task Detail screen: which actions exist, what they mean, and how the screen's state
 * maps into the contract's [ScreenState].
 *
 * The screen behind it is the real [TaskDetailCoordinator]. There is no second path into
 * the screen: an accepted action ends at [TaskDetailCoordinator.onIntent], the same entry
 * point a tap uses.
 */
class TaskDetailScreenIntrospection(private val coordinator: TaskDetailCoordinator) {
    private var deleteDispatched = false

    private val taskActions: List<ScreenAction> = taskDetailActions()

    private val introspection = ScreenIntrospection(screenId = TASK_DETAIL_SCREEN_ID, actions = taskActions)

    val actions: List<ScreenAction> = taskActions

    fun describe(): JsonObject = introspection.describe(stateOf(coordinator.stateFlow.value))

    fun describe(state: TaskDetailUiState): JsonObject = introspection.describe(stateOf(state))

    fun snapshot(): JsonObject = introspection.snapshot(stateOf(coordinator.stateFlow.value))

    fun snapshot(state: TaskDetailUiState): JsonObject = introspection.snapshot(stateOf(state))

    fun dispatch(
        action: String,
        input: JsonObject = JsonObject(emptyMap()),
    ): Result<Unit> = dispatch(coordinator.stateFlow.value, action, input)

    fun dispatch(
        state: TaskDetailUiState,
        action: String,
        input: JsonObject = JsonObject(emptyMap()),
    ): Result<Unit> {
        val result = introspection.dispatch(stateOf(state), action, input)
        if (result.isSuccess) {
            coordinator.onIntent(intentFor(action, input))
            if (action == ACTION_DELETE_TASK) {
                deleteDispatched = true
            }
        }
        return result
    }

    fun stateOf(state: TaskDetailUiState): ScreenState =
        when (state) {
            TaskDetailUiState.Loading -> {
                ScreenState(status = STATUS_LOADING)
            }

            is TaskDetailUiState.Error -> {
                ScreenState(status = STATUS_ERROR, message = state.message)
            }

            is TaskDetailUiState.Loaded -> {
                ScreenState(
                    status = STATUS_LOADED,
                    task = state.ui.taskSnapshot(),
                    focus =
                        buildJsonObject {
                            put("taskId", state.ui.task.id.value)
                            put("title", state.ui.titleDraft.value)
                        },
                )
            }
        }

    private fun taskDetailActions(): List<ScreenAction> =
        listOf(
            ScreenAction(
                name = ACTION_SET_PRIORITY,
                summary = "Set the task's priority.",
                requirement = REQUIREMENT_LOADED,
                inputSchema =
                    schema(
                        required = listOf("priority"),
                        properties = mapOf("priority" to priorityProperty()),
                    ),
                isAvailable = { state -> state.status == STATUS_LOADED },
            ),
            ScreenAction(
                name = ACTION_ADD_CHECKLIST_ITEM,
                summary = "Add a checklist item to the task.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(required = listOf("title"), properties = mapOf("title" to titleProperty())),
                isAvailable = { state -> state.status == STATUS_LOADED },
            ),
            ScreenAction(
                name = ACTION_TOGGLE_COMPLETE,
                summary = "Mark the task done, or a done task not done.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(required = emptyList(), properties = emptyMap()),
                isAvailable = { state -> state.status == STATUS_LOADED },
            ),
            ScreenAction(
                name = ACTION_DELETE_TASK,
                summary = "Soft-delete the task. The screen offers an undo for it.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(required = emptyList(), properties = emptyMap()),
                isAvailable = { state -> state.status == STATUS_LOADED },
            ),
            ScreenAction(
                name = ACTION_RESTORE_TASK,
                summary = "Undo the delete: put the task back.",
                requirement = REQUIREMENT_UNDO,
                inputSchema = schema(required = emptyList(), properties = emptyMap()),
                isAvailable = { state -> state.status == STATUS_LOADED && deleteDispatched && isArchived(state) },
            ),
        )

    private fun intentFor(
        action: String,
        input: JsonObject,
    ): TaskDetailIntent.Domain =
        when (action) {
            ACTION_SET_PRIORITY -> TaskDetailIntent.Domain.SetPriority(priorityOf(input))
            ACTION_ADD_CHECKLIST_ITEM -> TaskDetailIntent.Domain.AddChecklistItem(titleOf(input))
            ACTION_TOGGLE_COMPLETE -> TaskDetailIntent.Domain.ToggleComplete
            ACTION_DELETE_TASK -> TaskDetailIntent.Domain.Delete
            ACTION_RESTORE_TASK -> TaskDetailIntent.Domain.Restore
            else -> throw IllegalArgumentException("Unknown action '$action'.")
        }

    /** The part of the task the screen shows, and nothing else. */
    private fun TaskDetailUi.taskSnapshot(): JsonObject =
        buildJsonObject {
            put("id", task.id.value)
            put("title", titleDraft.value)
            put("priority", task.priority.wireName)
            put("kind", task.kind.wireName)
            put("completed", task.completedAt != null)
            put("pinned", task.isPinned)
            put("archived", task.archivedAt != null)
            put("project", project?.name)
            put("tags", JsonArray(tags.map { JsonPrimitive(it.name) }))
            put(
                "checklist",
                JsonArray(
                    checklist.map { item ->
                        buildJsonObject {
                            put("id", item.id.value)
                            put("title", item.title)
                            put("done", item.isCompleted)
                        }
                    },
                ),
            )
            put("subtasks", JsonArray(subtasks.map { JsonPrimitive(it.title) }))
            put("reminders", JsonArray(reminders.map { JsonPrimitive(it.offsetMinutes) }))
        }

    /** A JSON Schema for the action's input. */
    private fun schema(
        required: List<String>,
        properties: Map<String, JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
            put("properties", JsonObject(properties))
            put("additionalProperties", false)
        }

    private fun priorityProperty(): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(TaskPriority.entries.map { JsonPrimitive(it.wireName) }))
        }

    private fun titleProperty(): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("pattern", "\\S")
        }

    private fun isArchived(state: ScreenState): Boolean =
        state.task
            ?.get("archived")
            ?.jsonPrimitive
            ?.booleanOrNull == true

    private fun titleOf(input: JsonObject): String =
        (input["title"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("'title' is required and must be a non-empty string")

    private fun priorityOf(input: JsonObject): TaskPriority {
        val raw =
            (input["priority"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("'priority' is required and must be a non-empty string")
        return TaskPriority.entries.firstOrNull { it.wireName == raw }
            ?: throw IllegalArgumentException(
                "'priority' must be one of ${TaskPriority.entries.joinToString { it.wireName }}",
            )
    }

    private companion object {
        /** The stable name of the screen this contract describes. */
        const val TASK_DETAIL_SCREEN_ID = "tasks.task-detail"

        const val STATUS_LOADING = "loading"
        const val STATUS_ERROR = "error"
        const val STATUS_LOADED = "loaded"

        const val REQUIREMENT_LOADED = "the screen has to be showing a task"
        const val REQUIREMENT_UNDO = "the screen has to be holding an undo for a deleted task"

        const val ACTION_SET_PRIORITY = "set_priority"
        const val ACTION_ADD_CHECKLIST_ITEM = "add_checklist_item"
        const val ACTION_TOGGLE_COMPLETE = "toggle_complete"
        const val ACTION_DELETE_TASK = "delete_task"
        const val ACTION_RESTORE_TASK = "restore_task"
    }
}
