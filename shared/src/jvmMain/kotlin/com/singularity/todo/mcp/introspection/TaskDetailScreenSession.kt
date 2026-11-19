package com.singularity.todo.mcp.introspection

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.llm.SCREEN_INTROSPECTION_CONTRACT_VERSION
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.tasks.domain.model.TaskAiDeps
import com.singularity.todo.feature.tasks.domain.model.TaskChildrenDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCollaborationDeps
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSchedulingDeps
import com.singularity.todo.feature.tasks.presentation.introspection.TaskDetailScreenIntrospection
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.Koin
import java.util.UUID

internal class TaskDetailScreenSession private constructor(
    override val params: JsonObject,
    private val coordinator: TaskDetailCoordinator,
    private val introspection: TaskDetailScreenIntrospection,
    private val scope: CoroutineScope,
    private val closeScope: () -> Unit,
) : ScreenSession {
    override val screenId: String = SCREEN_ID
    override val sessionId: String = "session-${UUID.randomUUID()}"

    @Volatile
    private var lastState: TaskDetailUiState = TaskDetailUiState.Loading

    @Volatile
    private var lastSnapshot: JsonObject = introspection.snapshot(TaskDetailUiState.Loading)

    private val operations = OperationTracker()
    private val lock = Any()
    private val invokeMutex = Mutex()

    init {
        scope.launch {
            coordinator.stateFlow.collect { state ->
                synchronized(lock) {
                    lastState = state
                    lastSnapshot = introspection.snapshot(state)
                }
            }
        }
    }

    override fun close() {
        try {
            closeScope()
        } finally {
            scope.cancel()
        }
    }

    override fun describe(): JsonObject =
        buildJsonObject {
            put("screen", screenId)
            put("sessionId", sessionId)
            put("params", params)
            put("contractVersion", SCREEN_INTROSPECTION_CONTRACT_VERSION)
            val described = introspection.describe(currentState())
            described.forEach { (k, v) -> if (k != "screen") put(k, v) }
        }

    override fun snapshot(): JsonObject =
        buildJsonObject {
            put("screen", screenId)
            put("sessionId", sessionId)
            put("params", params)
            val snap = synchronized(lock) { lastSnapshot }
            snap.forEach { (k, v) -> if (k != "screen") put(k, v) }
        }

    override fun actions(): JsonObject =
        buildJsonObject {
            put("screen", screenId)
            put("sessionId", sessionId)
            val described = introspection.describe(currentState())
            put("status", described["status"] ?: JsonPrimitive(statusOf(currentState())))
            described["focus"]?.let { put("focus", it) }
            described["actions"]?.let { put("actions", it) }
        }

    override suspend fun invoke(
        action: String,
        input: JsonObject,
    ): JsonObject =
        invokeMutex.withLock {
            // Serialize dispatch per session so completion/failure observation is correlated to this action.
            if (operations.hasInFlight()) {
                throw InspectorException(
                    InspectorErrorCode.ACTION_NOT_AVAILABLE,
                    "Another operation is still in progress for this session.",
                    details =
                        buildJsonObject {
                            put("screen", screenId)
                            put("sessionId", sessionId)
                            put("action", action)
                        },
                )
            }
            val beforeSnapshot = synchronized(lock) { lastSnapshot }
            val result = introspection.dispatch(action, input)
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "Action rejected."
                throw InspectorException(
                    classifyValidation(action, message),
                    message,
                    details =
                        buildJsonObject {
                            put("screen", screenId)
                            put("sessionId", sessionId)
                            put("action", action)
                        },
                )
            }
            val op = operations.create(action)
            observeCompletion(op, action, input, beforeSnapshot)
            buildJsonObject {
                put("operationId", op.id)
                put("action", action)
                put("status", op.current.status.wire)
                op.current.message?.let { put("message", it) }
                put("snapshot", synchronized(lock) { lastSnapshot })
            }
        }

    override suspend fun wait(
        operationId: String,
        timeoutMs: Long,
    ): JsonObject {
        val op =
            operations.get(operationId)
                ?: throw InspectorException(
                    InspectorErrorCode.OPERATION_NOT_FOUND,
                    "Unknown operation '$operationId'.",
                    details =
                        buildJsonObject {
                            put("operationId", operationId)
                            put("sessionId", sessionId)
                        },
                )
        val record = op.await(timeoutMs)
        return buildJsonObject {
            put("operationId", record.id)
            put("action", record.action)
            put("status", record.status.wire)
            record.message?.let { put("message", it) }
            put("snapshot", synchronized(lock) { lastSnapshot })
        }
    }

    override fun cancel(operationId: String): JsonObject {
        val op =
            operations.get(operationId)
                ?: throw InspectorException(
                    InspectorErrorCode.OPERATION_NOT_FOUND,
                    "Unknown operation '$operationId'.",
                    details =
                        buildJsonObject {
                            put("operationId", operationId)
                            put("sessionId", sessionId)
                        },
                )
        if (op.isDone()) {
            return buildJsonObject {
                put("operationId", op.id)
                put("action", op.action)
                put("status", op.current.status.wire)
                op.current.message?.let { put("message", it) }
                put("snapshot", synchronized(lock) { lastSnapshot })
            }
        }
        // Once dispatched to the real screen/coordinator path we cannot safely stop execution;
        // reporting cancellation would falsely claim the side effect was prevented.
        throw InspectorException(
            InspectorErrorCode.UNSUPPORTED,
            "Cancellation of a dispatched screen action is not supported; the action may still complete.",
            details =
                buildJsonObject {
                    put("operationId", operationId)
                    put("action", op.action)
                    put("status", op.current.status.wire)
                    put("sessionId", sessionId)
                },
        )
    }

    private fun observeCompletion(
        op: OperationState,
        action: String,
        input: JsonObject,
        beforeSnapshot: JsonObject,
    ) {
        scope.launch {
            fun tryResolve(): Boolean {
                if (op.isDone()) return true
                val snap = synchronized(lock) { lastSnapshot }
                if (isActionCompleted(action, input, beforeSnapshot, snap)) {
                    operations.complete(op, OperationRecord(op.id, action, OperationStatus.COMPLETED))
                    return true
                }
                // Do not attribute arbitrary post-dispatch Error events to this action: the
                // coordinator can emit unrelated errors, and there is no action correlation in
                // TaskDetailUiEvent. When completion cannot be verified from state, leave the
                // operation pending rather than reporting a false FAILED/COMPLETED result.
                return false
            }

            if (tryResolve()) return@launch
            while (!op.isDone()) {
                delay(20)
                if (tryResolve()) return@launch
            }
        }
    }

    private fun isActionCompleted(
        action: String,
        input: JsonObject,
        beforeSnapshot: JsonObject,
        snapshot: JsonObject,
    ): Boolean {
        val status = snapshot["status"]?.jsonPrimitive?.contentOrNull ?: return false
        if (status != "loaded") return false
        val subject = snapshot["subject"] as? JsonObject ?: return false
        val beforeSubject = beforeSnapshot["subject"] as? JsonObject
        return when (action) {
            "set_priority" -> {
                // Idempotent: post-condition satisfied when the task has the requested priority
                // (including a no-op when it was already set before dispatch).
                val expected = input["priority"]?.jsonPrimitive?.contentOrNull ?: return false
                subject.get("priority")?.jsonPrimitive?.contentOrNull == expected
            }

            "toggle_complete" -> {
                val beforeCompleted = beforeSubject.boolOrNull("completed") ?: return false
                val nowCompleted = subject.boolOrNull("completed") ?: return false
                nowCompleted != beforeCompleted
            }

            "add_checklist_item" -> {
                val title = input["title"]?.jsonPrimitive?.contentOrNull ?: return false
                val beforeCount = beforeSnapshot.checklistTitles().count { it == title }
                val nowCount = snapshot.checklistTitles().count { it == title }
                nowCount > beforeCount
            }

            "delete_task" -> {
                // Idempotent: success when the task is archived (no-op if already archived).
                subject.boolOrNull("archived") == true
            }

            "restore_task" -> {
                // Idempotent: success when the task is not archived (no-op if already active).
                subject.boolOrNull("archived") == false
            }

            else -> {
                // Unknown/custom action: require a post-dispatch loaded snapshot change.
                snapshot != beforeSnapshot
            }
        }
    }

    private fun JsonObject?.boolOrNull(key: String): Boolean? = this?.get(key)?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.checklistTitles(): List<String> {
        val subject = this["subject"] as? JsonObject ?: return emptyList()
        val items = subject["checklist"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull }
    }

    private fun classifyValidation(
        action: String,
        message: String,
    ): InspectorErrorCode =
        when {
            message.startsWith("Unknown action") -> InspectorErrorCode.UNKNOWN_ACTION

            message.contains("not available") -> InspectorErrorCode.ACTION_NOT_AVAILABLE

            message.contains("rejected the input") ||
                message.contains("unexpected propert") ||
                message.contains("is required") ||
                message.contains("must be") -> InspectorErrorCode.INVALID_ACTION_INPUT

            else -> InspectorErrorCode.INVALID_ARGUMENTS
        }

    private fun currentState(): TaskDetailUiState = synchronized(lock) { lastState }

    private fun statusOf(state: TaskDetailUiState): String =
        when (state) {
            TaskDetailUiState.Loading -> "loading"
            is TaskDetailUiState.Error -> "error"
            is TaskDetailUiState.Loaded -> "loaded"
        }

    companion object {
        const val SCREEN_ID = "tasks.task-detail"

        fun open(
            taskId: TaskId,
            params: JsonObject,
            koin: Koin,
        ): TaskDetailScreenSession {
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(scopeJob + Dispatchers.Default)
            val closeableScope = AutoCloseableCoroutineScope(scope.coroutineContext)
            val coordinator = buildCoordinator(taskId, koin, closeableScope)
            val introspection = TaskDetailScreenIntrospection(coordinator)
            return TaskDetailScreenSession(
                params = params,
                coordinator = coordinator,
                introspection = introspection,
                scope = scope,
                closeScope = { closeableScope.close() },
            )
        }

        private fun buildCoordinator(
            taskId: TaskId,
            koin: Koin,
            scope: AutoCloseableCoroutineScope,
        ): TaskDetailCoordinator {
            val deps =
                TaskDetailDeps(
                    core =
                        TaskCoreDeps(
                            taskRepo = koin.get(),
                            updateTask = koin.get(),
                            createTask = koin.get(),
                            completeRecurring = koin.get(),
                            completion = koin.get(),
                            updateSeries = koin.get(),
                            detachFromSeries = koin.get(),
                            startRecurringSeries = koin.get(),
                        ),
                    children =
                        TaskChildrenDeps(
                            checklistRepository = koin.get(),
                            attachmentsRepo = koin.get(),
                            projectsRepo = koin.get(),
                            tagsRepo = koin.get(),
                        ),
                    scheduling =
                        TaskSchedulingDeps(
                            reminderRepo = koin.get(),
                            reminderScheduler = koin.get(),
                            timeZoneProvider = koin.get(),
                            notifier = koin.get(),
                        ),
                    collaboration =
                        TaskCollaborationDeps(
                            notesRepo = koin.get(),
                            timeTrackingRepo = koin.get(),
                            currentUser = koin.get(),
                            linkRepo = koin.getOrNull(),
                            proposals = koin.getOrNull(),
                            applyProposal = koin.getOrNull(),
                            editProposal = koin.getOrNull(),
                        ),
                    ai =
                        TaskAiDeps(
                            refineTask = koin.getOrNull(),
                            generateDescription = koin.getOrNull(),
                            generateChecklist = koin.getOrNull(),
                            decomposeTask = koin.getOrNull(),
                            pickTime = koin.getOrNull(),
                            firstStep = koin.getOrNull(),
                        ),
                    context = TaskContextDeps(clock = koin.get()),
                    linkPreview = koin.getOrNull(),
                )
            val crashReporter: CrashReportingPort = koin.getOrNull() ?: NoOpCrashReportingPort()
            return TaskDetailCoordinator(
                deps = deps,
                taskId = taskId,
                sourceViewId = null,
                crashReporter = crashReporter,
                scope = scope,
            )
        }
    }
}
