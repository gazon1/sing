package com.singularity.todo.feature.tasks.introspection

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A headless contract for the Task Detail screen: what it shows, and what it accepts.
 *
 * The screen behind it is a real [TaskDetailCoordinator]. This class adds no logic of
 * its own: it reads the coordinator's state into an allowlisted JSON snapshot, and it
 * turns a named action plus structured input into the [TaskDetailIntent.Domain] the
 * screen would have received from a tap. There is no second path into the screen —
 * every accepted action ends at [TaskDetailCoordinator.onIntent].
 *
 * ## Why it lives in the test source set
 *
 * This is a proof of concept, not a product surface. Nothing in the app can call it
 * and nothing in the app depends on it; a GO decision is what would move it (or a
 * driver for it) into the main source set.
 */
class TaskDetailScreenContract(private val coordinator: TaskDetailCoordinator) {
    /** The stable name of the screen this contract describes. */
    val screenId: String = "tasks.task-detail"

    /**
     * Whether this contract is holding an undo it could perform.
     *
     * The screen keeps its undo snapshot in its own lifecycle slot, and that snapshot
     * is deliberately not part of the assembled screen state — so the contract cannot
     * read it and has to track the delete it performed itself. Together with the
     * screen still showing the task as deleted, this is what says an undo is there
     * to be taken.
     */
    private var deleteDispatched = false

    /**
     * One action the screen accepts, with the shape of its input.
     *
     * The [toIntent] is the whole adapter: it turns the input into the intent the
     * screen would have received, and that intent goes to
     * [TaskDetailCoordinator.onIntent] — the same entry point a tap uses.
     */
    class Action(
        val name: String,
        val summary: String,
        /** What has to be true of the screen for this action to do something. */
        val requirement: String,
        val inputSchema: JsonObject,
        private val available: (TaskDetailUiState) -> Boolean = { true },
        private val toIntent: (JsonObject) -> TaskDetailIntent.Domain,
    ) {
        /** The properties the published schema defines. */
        private val accepted: Set<String> = (inputSchema["properties"] as? JsonObject)?.keys.orEmpty()

        /** Whether the screen can act on this action in the given state. */
        fun isAvailable(state: TaskDetailUiState): Boolean = available(state)

        /**
         * Whether the input matches the published schema exactly.
         *
         * A property the schema does not define is refused, not ignored. Accepting one
         * would let an agent believe a flag it invented took effect, while the screen
         * never saw it. An action whose schema takes no input therefore takes no
         * property at all.
         *
         * This runs before the intent is built, so an input that does not fit the
         * schema never reaches the mapping.
         */
        internal fun validate(input: JsonObject): Result<Unit> {
            val unexpected = input.keys - accepted
            if (unexpected.isEmpty()) {
                return Result.success(Unit)
            }
            return Result.failure(
                IllegalArgumentException(
                    "unexpected propert${if (unexpected.size == 1) "y" else "ies"} " +
                        unexpected.sorted().joinToString { "'$it'" },
                ),
            )
        }

        /**
         * The intent this action stands for, or a refusal that says what is wrong.
         *
         * The input has already been checked against the published schema; what is
         * left is the value the screen needs — a required field that is missing, of
         * the wrong type, or outside the accepted set.
         */
        internal fun intentFor(input: JsonObject): Result<TaskDetailIntent.Domain> =
            try {
                Result.success(toIntent(input))
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
    }

    /**
     * The actions this screen exposes.
     *
     * A deliberate subset: the point of the proof of concept is that a small, explicit
     * list of named actions is enough to drive a real journey, not that every intent
     * of the coordinator has a name here.
     *
     * Every action here needs a task on the screen: without one, the coordinator's
     * slots have nothing to work on and the action would report success while doing
     * nothing at all.
     */
    val actions: List<Action> =
        listOf(
            Action(
                name = "set_priority",
                summary = "Set the task's priority.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(required = listOf("priority"), properties = priorityProperties()),
                available = ::screenLoaded,
            ) { input -> TaskDetailIntent.Domain.SetPriority(input.priority()) },
            Action(
                name = "add_checklist_item",
                summary = "Add a checklist item to the task.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(required = listOf("title"), properties = titleProperties()),
                available = ::screenLoaded,
            ) { input -> TaskDetailIntent.Domain.AddChecklistItem(input.requiredString("title")) },
            Action(
                name = "toggle_complete",
                summary = "Mark the task done, or a done task not done.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(required = emptyList(), properties = emptyMap()),
                available = ::screenLoaded,
            ) { TaskDetailIntent.Domain.ToggleComplete },
            Action(
                name = "delete_task",
                summary = "Soft-delete the task. The screen offers an undo for it.",
                requirement = REQUIREMENT_LOADED,
                inputSchema = schema(required = emptyList(), properties = emptyMap()),
                available = ::screenLoaded,
            ) { TaskDetailIntent.Domain.Delete },
            Action(
                name = "restore_task",
                summary = "Undo the delete: put the task back.",
                requirement = REQUIREMENT_UNDO,
                inputSchema = schema(required = emptyList(), properties = emptyMap()),
                available = { state -> screenLoaded(state) && undoPending(state) },
            ) { TaskDetailIntent.Domain.Restore },
        )

    /**
     * Which screen is being inspected, and what it offers.
     *
     * The description is what an agent reads first: the screen's stable name, the
     * state it is in, the node it is focused on, and the actions it accepts — each
     * with its input shape and whether it can be acted on right now.
     *
     * The `focus` object is published only while the screen is showing a task, and
     * carries exactly two fields: `taskId` — the task on the screen — and `title`,
     * the title the screen currently shows (its draft, so an edit in progress is
     * what a reader sees).
     */
    fun describe(): JsonObject = describe(coordinator.stateFlow.value)

    /** The description of the screen in the given state. */
    internal fun describe(state: TaskDetailUiState): JsonObject =
        buildJsonObject {
            put("screen", screenId)
            put("status", statusOf(state))
            put(
                "actions",
                JsonArray(
                    actions.map { action ->
                        buildJsonObject {
                            put("name", action.name)
                            put("summary", action.summary)
                            put("requirement", action.requirement)
                            put("available", action.isAvailable(state))
                            put("input", action.inputSchema)
                        }
                    },
                ),
            )
            val focused = (state as? TaskDetailUiState.Loaded)?.ui
            if (focused != null) {
                put(
                    "focus",
                    buildJsonObject {
                        put("taskId", focused.task.id.value)
                        put("title", focused.titleDraft.value)
                    },
                )
            }
        }

    /**
     * What the screen currently shows, as an allowlisted JSON object.
     *
     * The fields are the ones a reader needs to understand the screen. The domain
     * graph behind them is not serialized: no credentials, no profile data, and no
     * property of the task that the screen does not show.
     */
    fun snapshot(): JsonObject =
        buildJsonObject {
            val state = coordinator.stateFlow.value
            put("screen", screenId)
            put("status", statusOf(state))
            when (state) {
                TaskDetailUiState.Loading -> Unit
                is TaskDetailUiState.Error -> put("message", state.message)
                is TaskDetailUiState.Loaded -> put("task", state.ui.taskSnapshot())
            }
        }

    /**
     * Dispatches a named action.
     *
     * The input is checked before anything is dispatched: an unknown action, an action
     * the screen cannot act on right now, a property the action's schema does not
     * define, or a malformed input comes back as a failure that names the supported
     * actions and the shape the input had to have. The schema check runs before the
     * intent is built, so an input that does not fit never reaches the mapping.
     *
     * A successful dispatch means the action was **accepted and handed to the
     * coordinator** — not that the write it started has landed. The screen does that
     * work asynchronously, so the outcome shows up in the next [snapshot], not in the
     * result of this call.
     */
    fun dispatch(
        action: String,
        input: JsonObject = JsonObject(emptyMap()),
    ): Result<Unit> = dispatch(coordinator.stateFlow.value, action, input)

    /** Dispatches a named action against the given screen state. */
    internal fun dispatch(
        state: TaskDetailUiState,
        action: String,
        input: JsonObject = JsonObject(emptyMap()),
    ): Result<Unit> {
        val spec =
            actions.firstOrNull { it.name == action }
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Unknown action '$action'. Supported actions: ${supportedActions()}",
                    ),
                )
        if (!spec.isAvailable(state)) {
            return Result.failure(
                IllegalArgumentException(
                    "Action '${spec.name}' is not available right now: ${spec.requirement} " +
                        "(the screen is '${statusOf(state)}'). " +
                        "Supported actions: ${supportedActions()}",
                ),
            )
        }
        return spec.validate(input).fold(
            onSuccess = {
                spec.intentFor(input).fold(
                    onSuccess = { intent ->
                        coordinator.onIntent(intent)
                        if (spec.name == "delete_task") {
                            deleteDispatched = true
                        }
                        Result.success(Unit)
                    },
                    onFailure = { error -> Result.failure(rejection(spec, error.message)) },
                )
            },
            onFailure = { error -> Result.failure(rejection(spec, error.message)) },
        )
    }

    /** The names of the actions this screen accepts. */
    private fun supportedActions(): String = actions.joinToString { it.name }

    /** The refusal an action answers with: what was wrong, what was expected, what is supported. */
    private fun rejection(
        spec: Action,
        reason: String?,
    ): IllegalArgumentException =
        IllegalArgumentException(
            "Action '${spec.name}' rejected the input: $reason. " +
                "Expected input: ${spec.inputSchema}. " +
                "Supported actions: ${supportedActions()}",
        )

    /**
     * Whether the screen is showing a task.
     *
     * Every action needs one: while the screen is loading or has failed to load, the
     * coordinator's slots have no task to work on, and an action dispatched then would
     * report success while changing nothing.
     */
    private fun screenLoaded(state: TaskDetailUiState): Boolean = state is TaskDetailUiState.Loaded

    /**
     * Whether an undo is there to be taken.
     *
     * Two things have to hold: this screen performed a delete, and the screen still
     * shows the task as deleted. The second one is what keeps the first one honest —
     * a delete that failed leaves the task alone, so there is nothing to undo, and a
     * restore that succeeded took the task out of the deleted state, so the undo is
     * spent.
     *
     * It is deliberately *not* tied to the restore attempt: the screen keeps its undo
     * snapshot when a restore fails, so that the user can try again, and the contract
     * has to offer the same retry.
     */
    private fun undoPending(state: TaskDetailUiState): Boolean {
        val ui = (state as? TaskDetailUiState.Loaded)?.ui ?: return false
        return deleteDispatched && ui.task.archivedAt != null
    }

    private companion object {
        /** What an action needs before the screen can act on it. */
        const val REQUIREMENT_LOADED = "the screen has to be showing a task"

        /** What the undo needs before the screen can act on it. */
        const val REQUIREMENT_UNDO = "the screen has to be holding an undo for a deleted task"
    }
}

/** The screen's state as a word, for the snapshot and the description. */
private fun statusOf(state: TaskDetailUiState): String =
    when (state) {
        TaskDetailUiState.Loading -> "loading"
        is TaskDetailUiState.Error -> "error"
        is TaskDetailUiState.Loaded -> "loaded"
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
        // The dispatcher refuses a property the schema does not define, so the schema
        // says so: JSON Schema allows additional properties by default, and a reader
        // that took the schema at its word would send input this screen refuses.
        put("additionalProperties", false)
    }

private fun priorityProperties(): Map<String, JsonObject> =
    mapOf(
        "priority" to
            buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(TaskPriority.entries.map { JsonPrimitive(it.wireName) }))
            },
    )

private fun titleProperties(): Map<String, JsonObject> =
    mapOf(
        "title" to
            buildJsonObject {
                put("type", "string")
                // The dispatcher rejects a blank title, so the schema says so.
                put("minLength", 1)
                put("pattern", "\\S")
            },
    )

/** The named field, read as a non-empty string. */
private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("'$name' is required and must be a non-empty string")

/** The named field, read as one of the task priorities. */
private fun JsonObject.priority(): TaskPriority {
    val raw = requiredString("priority")
    return TaskPriority.entries.firstOrNull { it.wireName == raw }
        ?: throw IllegalArgumentException(
            "'priority' must be one of ${TaskPriority.entries.joinToString { it.wireName }}",
        )
}
