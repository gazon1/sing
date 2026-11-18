@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks.introspection

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.SlotFakes
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.task
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.awaitState
import com.singularity.todo.test.helpers.testVm
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The proof of concept: a headless, agent-readable contract over the Task Detail screen.
 *
 * The screen is the real [TaskDetailCoordinator] with the real fakes behind it. What is
 * new here is only the way it is observed and driven: a JSON snapshot of what the screen
 * shows, and named actions that go through the same [TaskDetailCoordinator.onIntent] a tap
 * goes through.
 */
@Tag("fast")
@Epic("Tasks")
@Feature("Introspection")
class TaskDetailScreenContractTest {
    @Test
    fun `a journey inspects the screen, changes it, and undoes the delete`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1", title = "Original"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // The loaded screen, as an agent sees it.
            val snapshot = screen.snapshot()
            assertEquals("tasks.task-detail", snapshot.text("screen"))
            assertEquals("loaded", snapshot.text("status"))
            assertEquals("Original", snapshot.obj("task")?.text("title"))
            assertEquals("none", snapshot.obj("task")?.text("priority"))
            assertTrue(screen.actions.map { it.name }.contains("set_priority"))
            assertEquals("t1", screen.describe().obj("focus")?.text("taskId"))
            // The undo is not on offer yet: nothing has been deleted.
            assertEquals(false, screen.describe().action("restore_task")?.bool("available"))

            // The priority changes through the contract, and the change is the real one.
            val priority = screen.dispatch("set_priority", buildJsonObject { put("priority", "high") })
            assertTrue(priority.isSuccess, "the action is accepted: $priority")
            awaitState { screen.snapshot().obj("task")?.text("priority") == "high" }
            assertEquals(
                TaskPriority.High,
                fakes.taskRepo.tasks.value["t1"]
                    ?.priority,
            )

            // A checklist item is added the same way.
            val item = screen.dispatch("add_checklist_item", buildJsonObject { put("title", "Step one") })
            assertTrue(item.isSuccess, "the action is accepted: $item")
            awaitState {
                screen
                    .snapshot()
                    .obj("task")
                    ?.array("checklist")
                    ?.size == 1
            }
            assertEquals(1, fakes.checklistRepo.items.value.size)

            // The task is deleted, and the screen says so.
            val delete = screen.dispatch("delete_task")
            assertTrue(delete.isSuccess, "the action is accepted: $delete")
            awaitState { screen.snapshot().obj("task")?.bool("archived") == true }
            assertNotNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.archivedAt,
            )
            // The undo is on offer now, and it is the same one the screen offers.
            assertEquals(true, screen.describe().action("restore_task")?.bool("available"))

            // The undo puts it back through the same path.
            val undo = screen.dispatch("restore_task")
            assertTrue(undo.isSuccess, "the action is accepted: $undo")
            awaitState { screen.snapshot().obj("task")?.bool("archived") == false }
            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.archivedAt,
            )
            // And it is not on offer twice.
            assertEquals(false, screen.describe().action("restore_task")?.bool("available"))
        }

    @Test
    fun `an undo that failed stays on offer until it succeeds`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val events = mutableListOf<TaskDetailUiEvent>()
            val screen = screenFor(fakes, events)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // The task is deleted, so the undo is on offer.
            assertTrue(screen.dispatch("delete_task").isSuccess)
            awaitState { screen.snapshot().obj("task")?.bool("archived") == true }
            assertEquals(true, screen.describe().action("restore_task")?.bool("available"))

            // The restore fails. The screen keeps its undo snapshot on purpose — the
            // task would be lost if the snapshot went with the failure — so the undo
            // has to stay on offer: the user is expected to try again.
            fakes.taskRepo.restoreOverride = Result.failure(RuntimeException("Restore failed"))
            assertTrue(screen.dispatch("restore_task").isSuccess)
            awaitState {
                events.any { it is TaskDetailUiEvent.Error && it.message == "Restore failed" }
            }
            assertEquals(
                true,
                screen.snapshot().obj("task")?.bool("archived"),
                "the task is still deleted after the failed undo",
            )
            assertEquals(
                true,
                screen.describe().action("restore_task")?.bool("available"),
                "the undo is still on offer after it failed",
            )

            // The retry succeeds: the task is back, and the undo is spent.
            fakes.taskRepo.restoreOverride = null
            assertTrue(screen.dispatch("restore_task").isSuccess)
            awaitState { screen.snapshot().obj("task")?.bool("archived") == false }
            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.archivedAt,
            )
            assertEquals(
                false,
                screen.describe().action("restore_task")?.bool("available"),
                "the undo is spent once it has worked",
            )
        }

    @Test
    fun `the actions are unavailable while the screen is not showing a task`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // While the screen is loading or has failed, the coordinator's slots have no
            // task to work on: an action dispatched then would report success while
            // changing nothing, so none of them is on offer.
            val loading = TaskDetailUiState.Loading
            val error = TaskDetailUiState.Error("Not found")
            assertTrue(screen.actions.none { it.isAvailable(loading) }, "nothing is available while loading")
            assertTrue(screen.actions.none { it.isAvailable(error) }, "nothing is available after a failure")

            // And a dispatch in either state is refused before the coordinator sees it.
            val refusedLoading = screen.dispatch(loading, "delete_task")
            assertTrue(refusedLoading.isFailure, "the action is refused while loading")
            assertTrue(
                refusedLoading.exceptionOrNull()?.message?.contains("loading") == true,
                "the refusal names the screen state: ${refusedLoading.exceptionOrNull()?.message}",
            )
            val refusedError = screen.dispatch(error, "set_priority")
            assertTrue(refusedError.isFailure, "the action is refused after a failure")

            // Nothing reached the repository.
            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.archivedAt,
            )
            assertEquals(
                TaskPriority.None,
                fakes.taskRepo.tasks.value["t1"]
                    ?.priority,
            )
        }

    @Test
    fun `a screen that failed to load refuses every action`() =
        runTest {
            val fakes = SlotFakes()
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "error" }

            // The description says so, and every dispatch is refused.
            val described = screen.describe()
            assertEquals("error", described.text("status"))
            assertTrue(
                described.array("actions")?.all { it.jsonObject["available"]?.jsonPrimitive?.content == "false" } ==
                    true,
                "no action is advertised on a screen that failed to load",
            )
            screen.actions.forEach { action ->
                val result = screen.dispatch(action.name)
                assertTrue(result.isFailure, "'${action.name}' is refused: $result")
            }

            // And nothing was written.
            assertTrue(
                fakes.taskRepo.tasks.value
                    .isEmpty(),
                "the repository was not touched",
            )
        }

    @Test
    fun `an undo that cannot be taken is refused instead of silently doing nothing`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // Nothing has been deleted, so there is no undo to take: the action says so
            // instead of reporting a successful dispatch that does nothing.
            val refused = screen.dispatch("restore_task")
            assertTrue(refused.isFailure, "the undo is refused while there is nothing to undo")
            val message = refused.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("restore_task"), "the refusal names the action: $message")
            assertTrue(message.contains("set_priority"), "the refusal lists the actions: $message")
            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.archivedAt,
                "the refused action changed nothing",
            )

            // The description says the same thing, so an agent can see it before trying.
            val restore = screen.describe().action("restore_task")
            assertEquals(false, restore?.bool("available"), "the description marks the undo as unavailable")
        }

    @Test
    fun `an action the adapter does not expose is refused`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // `delete_series` is a real intent of the coordinator, and it is not exposed
            // here — the contract is the allow-list, and what is not in it is refused.
            val refused = screen.dispatch("delete_series")
            assertTrue(refused.isFailure, "the action is refused")
            val message = refused.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("delete_series"), "the refusal names the action: $message")
            assertTrue(message.contains("set_priority"), "the refusal lists the actions: $message")
            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.archivedAt,
            )
        }

    @Test
    fun `a malformed input is refused before the coordinator sees it`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            val missing = screen.dispatch("set_priority", JsonObject(emptyMap()))
            assertTrue(missing.isFailure, "the missing field is refused")
            assertTrue(
                missing.exceptionOrNull()?.message?.contains("priority") == true,
                "the refusal names the field: ${missing.exceptionOrNull()?.message}",
            )

            val wrongType = screen.dispatch("add_checklist_item", buildJsonObject { put("title", 42) })
            assertTrue(wrongType.isFailure, "the wrong type is refused")

            val blank = screen.dispatch("add_checklist_item", buildJsonObject { put("title", "   ") })
            assertTrue(blank.isFailure, "the blank title is refused")

            val unknownValue = screen.dispatch("set_priority", buildJsonObject { put("priority", "extreme") })
            assertTrue(unknownValue.isFailure, "the unknown value is refused")
            assertTrue(
                unknownValue.exceptionOrNull()?.message?.contains("high") == true,
                "the refusal lists the accepted values: ${unknownValue.exceptionOrNull()?.message}",
            )

            // Nothing reached the repository: the refusals happen before the dispatch.
            assertEquals(
                TaskPriority.None,
                fakes.taskRepo.tasks.value["t1"]
                    ?.priority,
            )
            assertEquals(0, fakes.checklistRepo.items.value.size)
        }

    @Test
    fun `an input with a property the schema does not define is refused`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // A property the schema does not define is refused rather than ignored:
            // an agent that believes a flag it invented took effect would be wrong.
            val extra =
                screen.dispatch(
                    "set_priority",
                    buildJsonObject {
                        put("priority", "high")
                        put("force", true)
                    },
                )
            assertTrue(extra.isFailure, "the extra property is refused")
            val message = extra.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("force"), "the refusal names the property: $message")
            assertEquals(
                TaskPriority.None,
                fakes.taskRepo.tasks.value["t1"]
                    ?.priority,
                "the refused input changed nothing",
            )

            // An action whose schema takes no input takes none at all.
            val noInput = screen.dispatch("toggle_complete", buildJsonObject { put("force", true) })
            assertTrue(noInput.isFailure, "an action without input refuses any property")
            assertTrue(
                noInput.exceptionOrNull()?.message?.contains("force") == true,
                "the refusal names the property: ${noInput.exceptionOrNull()?.message}",
            )
            assertNull(
                fakes.taskRepo.tasks.value["t1"]
                    ?.completedAt,
                "the refused input changed nothing",
            )

            // The input the schema does describe is still accepted.
            assertTrue(screen.dispatch("set_priority", buildJsonObject { put("priority", "high") }).isSuccess)
        }

    @Test
    fun `the description publishes the focused task the way the decision note says`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1", title = "Original"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // The focus object carries exactly the task id and the title the screen
            // shows — the shape the decision note documents.
            val focus = screen.describe().obj("focus")
            assertEquals(setOf("taskId", "title"), focus?.keys)
            assertEquals("t1", focus?.text("taskId"))
            assertEquals("Original", focus?.text("title"))

            // A screen that is not showing a task publishes no focus at all.
            assertNull(screen.describe(TaskDetailUiState.Loading).obj("focus"))
        }

    @Test
    fun `a successful dispatch is an accepted action, not a completed operation`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))

            // The write waits at a gate this test opens. That is what makes "the
            // operation has not completed" a fact about the test rather than about the
            // dispatcher: the write cannot finish before the gate opens, whatever the
            // dispatcher does with the coroutine that carries it.
            val gate = CompletableDeferred<Unit>()
            val gated = GatedTaskRepository(fakes.taskRepo, gate)
            val screen =
                screenFor(
                    fakes,
                    deps =
                        fakes.deps().copy(
                            core =
                                fakes.core().copy(
                                    taskRepo = gated,
                                    updateTask = UpdateTaskUseCase(gated, FakeClock()),
                                ),
                        ),
                )
            awaitState { screen.snapshot().text("status") == "loaded" }

            // The dispatch returns while the write is still held at the gate: it hands
            // the action to the coordinator, it does not wait for the operation to
            // finish. A dispatch that waited would never return.
            assertTrue(
                screen.dispatch("set_priority", buildJsonObject { put("priority", "high") }).isSuccess,
            )
            assertEquals(
                TaskPriority.None,
                fakes.taskRepo.tasks.value["t1"]
                    ?.priority,
                "the operation has not completed while it is held at the gate",
            )

            // The gate opens: the operation completes, and the outcome shows up in the
            // next snapshot.
            gate.complete(Unit)
            awaitState { screen.snapshot().obj("task")?.text("priority") == "high" }
            assertEquals(
                TaskPriority.High,
                fakes.taskRepo.tasks.value["t1"]
                    ?.priority,
            )
        }

    @Test
    fun `the published schema says the same thing as the dispatcher`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            // The checklist item takes a `title`, and the schema says so.
            val checklist = screen.describe().action("add_checklist_item")
            val schema = checklist?.obj("input")
            assertEquals(
                listOf("title"),
                schema?.array("required")?.map { it.jsonPrimitive.content },
            )
            val title = schema?.obj("properties")?.obj("title")
            assertEquals("string", title?.text("type"))
            // The dispatcher rejects a blank title, so the schema says so as well.
            assertEquals(
                1,
                title
                    ?.get("minLength")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )
            assertTrue(
                title
                    ?.get("pattern")
                    ?.jsonPrimitive
                    ?.content
                    ?.contains("\\S") == true,
            )
            // Every published schema says the same thing the dispatcher does: no
            // property outside the schema is accepted. JSON Schema allows additional
            // properties by default, so without this a reader would send input the
            // screen refuses.
            screen.actions.forEach { action ->
                val published = screen.describe().action(action.name)?.obj("input")
                assertEquals(
                    false,
                    published?.get("additionalProperties")?.jsonPrimitive?.booleanOrNull,
                    "'${action.name}' publishes a schema that allows no extra property",
                )
            }
        }

    @Test
    fun `the snapshot carries only the allowlisted fields`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            val snapshot = screen.snapshot()
            assertEquals(setOf("screen", "status", "task"), snapshot.keys)
            assertEquals(
                setOf(
                    "id",
                    "title",
                    "priority",
                    "kind",
                    "completed",
                    "pinned",
                    "archived",
                    "project",
                    "tags",
                    "checklist",
                    "subtasks",
                    "reminders",
                ),
                snapshot.obj("task")?.keys,
            )
        }

    @Test
    fun `the screen describes itself and lists the actions it accepts`() =
        runTest {
            val fakes = SlotFakes()
            fakes.taskRepo.seed(task("t1", title = "Original"))
            val screen = screenFor(fakes)
            awaitState { screen.snapshot().text("status") == "loaded" }

            val described = screen.describe()
            assertEquals("tasks.task-detail", described.text("screen"))
            assertEquals("loaded", described.text("status"))
            assertEquals("Original", described.obj("focus")?.text("title"))
            assertEquals(
                screen.actions.map { it.name },
                described.array("actions")?.map { it.jsonObject["name"]?.jsonPrimitive?.content },
            )
            // Every action carries its own shape, so an agent can read the contract
            // without a second lookup.
            assertTrue(
                described.array("actions")?.all { it.jsonObject["input"]?.jsonObject != null } == true,
            )
        }

    /**
     * The real coordinator, with the real fakes behind it.
     *
     * The scope is the background one: the coordinator collects flows that never end, so
     * its work must not hold the test open — that is what [VmScope.Background] is for.
     *
     * [events] collects the coordinator's one-shot events, which is how a test can see
     * that a write it made failed: the failure is reported as an event, not as state.
     */
    private fun TestScope.screenFor(
        fakes: SlotFakes,
        events: MutableList<TaskDetailUiEvent> = mutableListOf(),
        deps: TaskDetailDeps = fakes.deps(),
    ): TaskDetailScreenContract {
        val ctx =
            testVm<TaskDetailCoordinator, TaskDetailUiState>(
                stateAccessor = { vm: TaskDetailCoordinator -> vm.state },
                vmScope = VmScope.Background,
                afterInit = { vm, bgScope ->
                    bgScope.launch { vm.events.collect { events += it } }
                },
            ) { scope ->
                TaskDetailCoordinator(
                    deps = deps,
                    taskId = TaskId("t1"),
                    scope = scope,
                )
            }
        return TaskDetailScreenContract(ctx.vm)
    }
}

/**
 * A task repository whose writes wait at a gate the test opens.
 *
 * The gate is what lets a test say "this operation has not completed yet" without
 * depending on how a dispatcher happens to schedule the coroutine that carries the
 * write: the write cannot finish before the gate opens, on any dispatcher.
 */
private class GatedTaskRepository(private val delegate: TaskRepository, private val gate: CompletableDeferred<Unit>) :
    TaskRepository by delegate {
    override suspend fun update(item: Task): Result<Task> {
        gate.await()
        return delegate.update(item)
    }
}

/** The named field, read as a string. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The named field, read as a boolean. */
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** The named field, read as a nested object. */
private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

/** The named field, read as an array. */
private fun JsonObject.array(key: String) = this[key]?.jsonArray

/** The named action, as the description publishes it. */
private fun JsonObject.action(name: String): JsonObject? =
    array("actions")?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content == name }
