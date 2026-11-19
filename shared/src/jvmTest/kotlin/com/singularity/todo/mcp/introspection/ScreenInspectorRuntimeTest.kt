@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.mcp.introspection

import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.jvmPlatformModule
import com.singularity.todo.feature.profile.ProfileBootstrapper
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@Epic("Agent")
@Feature("Headless screen inspector")
@Tag("slow")
@Tag("slow:data")
class ScreenInspectorRuntimeTest {
    @Test
    fun `agent can discover inspect invoke wait and verify task detail`() =
        withRuntime { runtime, koin ->
            val taskRepo: TaskRepository = koin.get()
            val created =
                kotlinx.coroutines
                    .runBlocking {
                        val userId = taskRepo.currentUserId()
                        taskRepo.create(
                            Task(
                                id = TaskId("task-1"),
                                title = "Original",
                                priority = TaskPriority.None,
                                userId = userId,
                                createdAt = Clock.System.now(),
                                updatedAt = Clock.System.now(),
                            ),
                        )
                    }.getOrThrow()

            val caps = runtime.capabilities()
            val screens = caps["screens"]!!.jsonArray
            assertTrue(screens.any { it.jsonObject["id"]!!.jsonPrimitive.content == "tasks.task-detail" })

            val opened =
                runtime.open(
                    "tasks.task-detail",
                    buildJsonObject { put("taskId", created.id.value) },
                )
            val sessionId = opened["sessionId"]!!.jsonPrimitive.content
            assertEquals("tasks.task-detail", opened["screen"]!!.jsonPrimitive.content)

            awaitLoaded(runtime, sessionId)

            val described = runtime.describe(sessionId)
            assertEquals("loaded", described["status"]!!.jsonPrimitive.content)
            assertNotNull(described["focus"]?.jsonObject)
            val actions = described["actions"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue("set_priority" in actions)
            assertTrue("add_checklist_item" in actions)
            assertTrue(actions.contains("toggle_complete"))

            val unknown =
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        runtime.invoke(
                            sessionId,
                            "nope",
                            JsonObject(emptyMap()),
                        )
                    }
                }
            assertTrue(unknown.isFailure)
            val unknownErr = unknown.exceptionOrNull() as InspectorException
            assertEquals(InspectorErrorCode.UNKNOWN_ACTION, unknownErr.code)

            val badInput =
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        runtime.invoke(
                            sessionId,
                            "set_priority",
                            buildJsonObject { put("priority", "extreme") },
                        )
                    }
                }
            assertTrue(badInput.isFailure)
            assertEquals(
                InspectorErrorCode.INVALID_ACTION_INPUT,
                (badInput.exceptionOrNull() as InspectorException).code,
            )

            val invoked =
                kotlinx.coroutines.runBlocking {
                    runtime.invoke(
                        sessionId,
                        "set_priority",
                        buildJsonObject { put("priority", "high") },
                    )
                }
            val opId = invoked["operationId"]!!.jsonPrimitive.content
            val invokeStatus = invoked["status"]!!.jsonPrimitive.content
            assertTrue(
                invokeStatus in setOf("accepted", "completed"),
                "invoke status: $invokeStatus payload=$invoked",
            )

            val waited =
                if (invokeStatus == "completed") {
                    invoked
                } else {
                    kotlinx.coroutines.runBlocking { runtime.wait(sessionId, opId, 5_000) }
                }
            assertEquals("completed", waited["status"]!!.jsonPrimitive.content, "wait result: $waited")

            awaitState(runtime, sessionId) {
                it.jsonObject["subject"]
                    ?.jsonObject
                    ?.get("priority")
                    ?.jsonPrimitive
                    ?.content == "high"
            }
            val verified = runtime.snapshot(sessionId)
            assertEquals(
                "high",
                verified["subject"]!!.jsonObject["priority"]!!.jsonPrimitive.content,
            )

            runtime.close(sessionId)
        }

    @Test
    fun `unknown screen and session errors are structural`() =
        withRuntime { runtime, _ ->
            val unknownScreen = runCatching { runtime.open("tasks.nope", JsonObject(emptyMap())) }
            assertTrue(unknownScreen.isFailure)
            assertEquals(
                InspectorErrorCode.UNKNOWN_SCREEN,
                (unknownScreen.exceptionOrNull() as InspectorException).code,
            )

            val unknownSession = runCatching { runtime.snapshot("missing-session") }
            assertTrue(unknownSession.isFailure)
            assertEquals(
                InspectorErrorCode.SESSION_NOT_FOUND,
                (unknownSession.exceptionOrNull() as InspectorException).code,
            )
        }

    @Test
    fun `wait timeout does not terminalize a pending operation`() =
        runBlocking {
            val tracker = OperationTracker()
            val op = tracker.create("toggle_complete")

            val timedOut = op.await(1)
            assertEquals(OperationStatus.TIMEOUT, timedOut.status)
            assertEquals(op.id, timedOut.id)
            assertEquals(false, op.isDone(), "wait timeout must not mark operation terminal")

            op.complete(OperationRecord(op.id, op.action, OperationStatus.COMPLETED))
            val done = op.await(1_000)
            assertEquals(OperationStatus.COMPLETED, done.status)
            assertEquals(true, op.isDone())
        }

    @Test
    fun `runtime wait observes completion after a non-terminal timeout response`() =
        withRuntime { runtime, koin ->
            val taskRepo: TaskRepository = koin.get()
            runBlocking {
                val userId = taskRepo.currentUserId()
                taskRepo.create(
                    Task(
                        id = TaskId("task-2"),
                        title = "Slow",
                        userId = userId,
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                    ),
                )
            }.getOrThrow()
            val sessionId =
                runtime
                    .open("tasks.task-detail", buildJsonObject { put("taskId", "task-2") })["sessionId"]!!
                    .jsonPrimitive
                    .content
            awaitLoaded(runtime, sessionId)

            val invoked =
                runBlocking {
                    runtime.invoke(sessionId, "toggle_complete", JsonObject(emptyMap()))
                }
            val opId = invoked["operationId"]!!.jsonPrimitive.content
            val waited = runBlocking { runtime.wait(sessionId, opId, 1) }
            assertEquals(opId, waited["operationId"]!!.jsonPrimitive.content)
            val waitStatus = waited["status"]!!.jsonPrimitive.content
            assertTrue(
                waitStatus in setOf("timeout", "completed", "failed"),
                "wait status: $waitStatus payload=$waited",
            )

            val done = runBlocking { runtime.wait(sessionId, opId, 10_000) }
            assertEquals("completed", done["status"]!!.jsonPrimitive.content, "wait result: $done")
            awaitState(runtime, sessionId) {
                it.jsonObject["subject"]
                    ?.jsonObject
                    ?.get("completed")
                    ?.jsonPrimitive
                    ?.booleanOrNull == true
            }
            runtime.close(sessionId)
        }

    @Test
    fun `cancel is unsupported for in-flight operations and unknown operations fail structurally`() =
        withRuntime { runtime, koin ->
            val taskRepo: TaskRepository = koin.get()
            kotlinx.coroutines
                .runBlocking {
                    val userId = taskRepo.currentUserId()
                    taskRepo.create(
                        Task(
                            id = TaskId("task-3"),
                            title = "Ops",
                            userId = userId,
                            createdAt = Clock.System.now(),
                            updatedAt = Clock.System.now(),
                        ),
                    )
                }.getOrThrow()
            val sessionId =
                runtime
                    .open("tasks.task-detail", buildJsonObject { put("taskId", "task-3") })["sessionId"]!!
                    .jsonPrimitive
                    .content
            awaitLoaded(runtime, sessionId)

            val invoked =
                kotlinx.coroutines.runBlocking {
                    runtime.invoke(sessionId, "toggle_complete", JsonObject(emptyMap()))
                }
            val opId = invoked["operationId"]!!.jsonPrimitive.content

            val cancel = runCatching { runtime.cancel(sessionId, opId) }
            if (cancel.isFailure) {
                assertEquals(
                    InspectorErrorCode.UNSUPPORTED,
                    (cancel.exceptionOrNull() as InspectorException).code,
                )
            } else {
                // Operation may have completed before cancel was attempted; then cancel reports terminal state.
                val status = cancel.getOrThrow()["status"]!!.jsonPrimitive.content
                assertTrue(status in setOf("completed", "failed"))
            }

            val missingCancel = runCatching { runtime.cancel(sessionId, "missing-op") }
            assertEquals(
                InspectorErrorCode.OPERATION_NOT_FOUND,
                (missingCancel.exceptionOrNull() as InspectorException).code,
            )
            val wait = runCatching { kotlinx.coroutines.runBlocking { runtime.wait(sessionId, "missing-op", 10) } }
            assertEquals(InspectorErrorCode.OPERATION_NOT_FOUND, (wait.exceptionOrNull() as InspectorException).code)

            // Dispatched action must still be able to complete (cancellation is not reported as stopping it).
            val done = kotlinx.coroutines.runBlocking { runtime.wait(sessionId, opId, 10_000) }
            assertTrue(done["status"]!!.jsonPrimitive.content in setOf("completed", "failed"))
            runtime.close(sessionId)
        }

    private fun withRuntime(block: (ScreenInspectorRuntime, Koin) -> Unit) {
        val tempHome =
            kotlin.io.path
                .createTempDirectory("screen-inspector-test")
                .toFile()
        val prevHome = System.getProperty("user.home")
        val prevName = System.getProperty("user.name")
        System.setProperty("user.home", tempHome.absolutePath)
        if (System.getProperty("user.name").isNullOrBlank()) System.setProperty("user.name", "test")
        val app =
            koinApplication {
                modules(
                    listOf(jvmPlatformModule(File(tempHome, "app")), coreLoggingModule()) +
                        domainModule(ProfileId.fromString("ai-agent")),
                )
            }
        try {
            val koin = app.koin
            kotlinx.coroutines.runBlocking { koin.get<ProfileBootstrapper>().run() }
            val runtime = ScreenInspectorRuntime(koin)
            try {
                block(runtime, koin)
            } finally {
                runtime.close()
            }
        } finally {
            runCatching { app.close() }
            if (prevHome != null) System.setProperty("user.home", prevHome) else System.clearProperty("user.home")
            if (prevName != null) System.setProperty("user.name", prevName)
        }
    }

    private fun awaitLoaded(
        runtime: ScreenInspectorRuntime,
        sessionId: String,
    ) {
        awaitState(runtime, sessionId) { it.jsonObject["status"]?.jsonPrimitive?.content == "loaded" }
    }

    private fun awaitState(
        runtime: ScreenInspectorRuntime,
        sessionId: String,
        predicate: (kotlinx.serialization.json.JsonElement) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val snap = runtime.snapshot(sessionId)
            if (predicate(snap)) return
            Thread.sleep(20)
        }
        val snap = runtime.snapshot(sessionId)
        error("Timed out waiting for state. snapshot=$snap")
    }
}
