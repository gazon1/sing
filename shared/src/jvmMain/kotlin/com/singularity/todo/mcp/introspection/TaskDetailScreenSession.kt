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
import com.singularity.todo.feature.tasks.domain.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.introspection.TaskDetailScreenIntrospection
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.Koin
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class TaskDetailScreenSession private constructor(
    override val params: JsonObject,
    private val coordinator: TaskDetailCoordinator,
    private val introspection: TaskDetailScreenIntrospection,
    private val scope: CoroutineScope,
    private val closeScope: () -> Unit,
) : ScreenSession {
    override val screenId: String = SCREEN_ID
    override val sessionId: String = "session-${UUID.randomUUID()}"

    private val seq = AtomicLong(0)

    @Volatile
    private var lastState: TaskDetailUiState = TaskDetailUiState.Loading

    @Volatile
    private var lastSnapshot: JsonObject = introspection.snapshot(TaskDetailUiState.Loading)

    private val events = ArrayDeque<Pair<Long, TaskDetailUiEvent>>()
    private val operations = OperationTracker()
    private val lock = Any()

    init {
        scope.launch {
            coordinator.stateFlow.collect { state ->
                val s = seq.incrementAndGet()
                synchronized(lock) {
                    lastState = state
                    lastSnapshot = introspection.snapshot(state)
                }
            }
        }
        scope.launch {
            coordinator.events.collect { event ->
                val s = seq.get()
                synchronized(lock) {
                    events.addLast(s to event)
                    if (events.size > 200) events.removeFirst()
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
    ): JsonObject {
        val beforeSeq = seq.get()
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
        observeCompletion(op, action, beforeSeq)
        return buildJsonObject {
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
            operations.cancel(operationId)
                ?: throw InspectorException(
                    InspectorErrorCode.OPERATION_NOT_FOUND,
                    "Unknown operation '$operationId'.",
                    details =
                        buildJsonObject {
                            put("operationId", operationId)
                            put("sessionId", sessionId)
                        },
                )
        return buildJsonObject {
            put("operationId", op.id)
            put("action", op.action)
            put("status", op.current.status.wire)
            op.current.message?.let { put("message", it) }
            put("snapshot", synchronized(lock) { lastSnapshot })
        }
    }

    private fun observeCompletion(
        op: OperationState,
        action: String,
        beforeSeq: Long,
    ) {
        scope.launch {
            while (!op.isDone()) {
                val err =
                    synchronized(lock) {
                        events
                            .lastOrNull { (s, event) ->
                                s >= beforeSeq && event is TaskDetailUiEvent.Error
                            }?.second as? TaskDetailUiEvent.Error
                    }
                if (err != null) {
                    op.complete(OperationRecord(op.id, action, OperationStatus.FAILED, message = err.message))
                    return@launch
                }
                if (seq.get() > beforeSeq) {
                    op.complete(OperationRecord(op.id, action, OperationStatus.COMPLETED))
                    return@launch
                }
                delay(20)
            }
        }
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
