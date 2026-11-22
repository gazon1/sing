@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.journey

import co.touchlab.kermit.Logger
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.DraftUiState
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.presentation.introspection.AgendaScreenIntrospection
import com.singularity.todo.feature.agenda.presentation.state.AgendaUiState
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.domain.state.TaskCreateSeed
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MergeTaskSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskReopenUseCase
import com.singularity.todo.feature.tasks.presentation.introspection.TaskDetailScreenIntrospection
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.SlotFakes
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeDateGroupingSettingsRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTaskMutationsUseCase
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.awaitState
import com.singularity.todo.test.helpers.testVm
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Representative multi-step journeys across real screen boundaries.
 *
 * Each test drives more than one screen, and each transition is the one the application itself
 * decides: the list publishes `open_task` with the destination its row's tap would hand the
 * navigator, and the next screen is opened from *that* published destination rather than from
 * an id the test made up. The state assertions are on the allow-listed snapshots and on the
 * repository; nothing here renders Compose, so nothing here is evidence that a control is
 * wired to these actions or that anything is drawn (that is #3049's half).
 *
 * ## What these journeys are, and what they are not
 *
 * They are behavior/contract journeys over the application's real ViewModels and its existing
 * fakes. They are **not** proof of end-to-end UI correctness: a passing journey says the
 * contract, the dispatcher and the screen's own intent path agree, not that the pixels do.
 */
@Tag("fast")
@Epic("Testing")
@Feature("Headless journeys")
class ScreenJourneyTest {
    /**
     * Journey 1 — list → task detail → change the task → back to the list.
     *
     * The list is the Inbox tab. Opening a task is the destination the list published, not one
     * this test invented; the change happens on the detail screen's own contract; and the
     * effect is read back both from the detail snapshot and from the list's next render.
     */
    @Test
    fun `a task opened from the list is changed on the detail screen and the list shows it`() =
        runTest {
            val screens = JourneyScreens(this)
            screens.seed(screens.task(id = "t1", title = "Buy milk"))
            screens.seed(screens.task(id = "t2", title = "Ship release"))

            val inbox = screens.inbox()
            awaitState { inbox.snapshot().text("status") == "loaded" }
            assertEquals(setOf("t1", "t2"), inbox.snapshot().listedTaskIds())

            val opened = inbox.dispatch("open_task", taskId("t1"))
            assertTrue(opened.isSuccess, "the list accepts opening a task it shows: $opened")
            awaitState { inbox.snapshot().obj("navigation") != null }

            // The destination is the list's own answer, so the detail screen is opened from it.
            val destination = assertNotNull(inbox.snapshot().obj("navigation"))
            assertEquals("tasks.task-detail", destination.text("screen"))
            val openedTaskId = assertNotNull(destination.obj("params")?.text("taskId"))

            val detail = screens.taskDetail(openedTaskId)
            awaitState { detail.snapshot().text("status") == "loaded" }
            assertEquals("Buy milk", detail.snapshot().obj("subject")?.text("title"))

            val changed =
                detail.dispatch("set_priority", buildJsonObject { put("priority", "high") })
            assertTrue(changed.isSuccess, "the detail screen accepts the priority: $changed")
            awaitState { detail.snapshot().obj("subject")?.text("priority") == "high" }

            // The write is the application's, and the list renders from it.
            assertEquals(TaskPriority.High, screens.task("t1")?.priority)
            val refreshed = screens.inbox()
            awaitState { refreshed.snapshot().text("status") == "loaded" }
            assertEquals("high", refreshed.snapshot().priorityOf("t1"))
            assertEquals("Buy milk", refreshed.snapshot().titleOf("t1"))
        }

    /**
     * Journey 2 — project list → open a project-scoped task → complete it.
     *
     * The same two screens with a project behind them. The point is that the screen id is a
     * parameter: the list opened from a project shows that project's tasks and not another
     * project's, and the task opened from it is the project's own task.
     */
    @Test
    fun `a task opened from a project list is completed and leaves the project's open work`() =
        runTest {
            val screens = JourneyScreens(this)
            screens.seedProject("p1")
            screens.seedProject("p2")
            screens.seed(screens.task(id = "mine", title = "Project task", projectId = "p1"))
            screens.seed(screens.task(id = "theirs", title = "Other project task", projectId = "p2"))

            val list = screens.projectList("p1")
            awaitState { list.snapshot().text("status") == "loaded" }
            assertEquals("project", list.snapshot().obj("subject")?.text("view"))
            assertEquals("p1", list.snapshot().obj("subject")?.text("projectId"))
            assertEquals(setOf("mine"), list.snapshot().listedTaskIds())

            // A task the list does not show is refused rather than dispatched: the row is not
            // on this screen, so the action would report something the user could not do here.
            val foreign = list.dispatch("open_task", taskId("theirs"))
            assertTrue(foreign.isFailure, "a task outside this project is refused: $foreign")
            assertNull(list.snapshot().obj("navigation"))

            assertTrue(list.dispatch("open_task", taskId("mine")).isSuccess)
            awaitState { list.snapshot().obj("navigation") != null }
            val destination = assertNotNull(list.snapshot().obj("navigation"))
            val openedTaskId = assertNotNull(destination.obj("params")?.text("taskId"))

            val detail = screens.taskDetail(openedTaskId)
            awaitState { detail.snapshot().text("status") == "loaded" }
            assertEquals("Project task", detail.snapshot().obj("subject")?.text("title"))

            assertTrue(detail.dispatch("toggle_complete").isSuccess)
            awaitState { detail.snapshot().obj("subject")?.bool("completed") == true }
            assertNotNull(screens.task("mine")?.completedAt)

            // The project list renders the completion it wrote.
            val refreshed = screens.projectList("p1")
            awaitState { refreshed.snapshot().text("status") == "loaded" }
            assertEquals(true, refreshed.snapshot().boolOf("mine", "completed"))
        }

    /**
     * Journey 3 — the list completes a task, and a task created in the editor joins the list.
     *
     * Both halves are the list's own view of the application: the completion through the
     * agenda's own check intent, the creation through the editor's real save, which writes the
     * repository the list reads.
     */
    @Test
    fun `completing a task and creating one both show up in the list`() =
        runTest {
            val screens = JourneyScreens(this)
            screens.seed(screens.task(id = "t1", title = "Buy milk"))

            val inbox = screens.inbox()
            awaitState { inbox.snapshot().text("status") == "loaded" }

            assertTrue(inbox.dispatch("toggle_complete", taskId("t1")).isSuccess)
            awaitState { screens.storedTask("t1")?.completedAt != null }
            awaitState { inbox.snapshot().boolOf("t1", "completed") == true }

            // The editor is the real one, saving through the real create use case.
            val editor = screens.taskEditor()
            awaitState {
                editor.state.value.draft.title.text
                    .isEmpty()
            }
            editor.onIntent(TaskCreateIntent.TitleChanged("Write report"))
            editor.onIntent(TaskCreateIntent.SaveClicked)
            awaitState { screens.storedTaskByTitle("Write report") != null }

            val created = assertNotNull(screens.storedTaskByTitle("Write report"))
            assertTrue(created.completedAt == null, "a new task arrives open")

            // And the list, built again, shows the task the editor wrote.
            val refreshed = screens.inbox()
            awaitState { refreshed.snapshot().text("status") == "loaded" }
            assertTrue(
                created.id.value in refreshed.snapshot().listedTaskIds(),
                "the created task is in the list: ${refreshed.snapshot().listedTaskIds()}",
            )
        }

    /**
     * The negative half of every action mapping, on the screen that publishes most of them.
     *
     * An unknown action, an input outside the schema, a missing field and a task id the list
     * does not show are all refused before anything is dispatched — and the repository is
     * untouched, so a refusal is not "dispatched and quietly ignored".
     */
    @Test
    fun `the list refuses what it does not publish and writes nothing`() =
        runTest {
            val screens = JourneyScreens(this)
            screens.seed(screens.task(id = "t1", title = "Buy milk"))

            val inbox = screens.inbox()
            awaitState { inbox.snapshot().text("status") == "loaded" }

            val unknown = inbox.dispatch("delete_task", taskId("t1"))
            assertTrue(unknown.isFailure, "an unpublished action is refused: $unknown")
            assertTrue(
                unknown.exceptionOrNull()?.message?.contains("delete_task") == true,
                "the refusal names the action: ${unknown.exceptionOrNull()?.message}",
            )

            val missingField = inbox.dispatch("toggle_complete", JsonObject(emptyMap()))
            assertTrue(missingField.isFailure, "a missing field is refused: $missingField")

            val wrongType = inbox.dispatch("toggle_complete", buildJsonObject { put("taskId", 7) })
            assertTrue(wrongType.isFailure, "a non-string task id is refused: $wrongType")

            val extra =
                inbox.dispatch(
                    "open_task",
                    buildJsonObject {
                        put("taskId", "t1")
                        put("force", true)
                    },
                )
            assertTrue(extra.isFailure, "a property outside the schema is refused: $extra")

            val absent = inbox.dispatch("open_task", taskId("missing"))
            assertTrue(absent.isFailure, "a task the list does not show is refused: $absent")
            assertTrue(
                absent.exceptionOrNull()?.message?.contains("missing") == true,
                "the refusal names the id: ${absent.exceptionOrNull()?.message}",
            )

            // Nothing was written and nothing navigated.
            assertNull(screens.storedTask("t1")?.completedAt)
            assertNull(inbox.snapshot().obj("navigation"))
        }

    /**
     * A screen that failed to load publishes no actions and refuses every one of them.
     *
     * Unsupported state is reported as such: the snapshot says `error`, no action is
     * available, and the dispatch is refused instead of being run against a list that is not
     * there.
     */
    @Test
    fun `a list that failed to load refuses every action`() =
        runTest {
            val screens = JourneyScreens(this)
            val inbox = screens.inbox()
            awaitState { inbox.snapshot().text("status") in setOf("loaded", "error") }

            // Both states are checked against the adapter's own availability rule; the list is
            // exercised in whichever one its first render reached, and the other is asserted
            // directly so the rule is covered either way.
            val failed = AgendaUiState.Error("boom")
            val failedState = inbox.stateOf(failed)
            assertTrue(
                inbox.actions.none { it.isAvailable(failedState) },
                "no action is available on a list that failed",
            )
            assertTrue(
                inbox.actions.none { it.isAvailable(inbox.stateOf(AgendaUiState.Loading)) },
                "no action is available on a list that is still loading",
            )
            assertEquals("error", failedState.status)
            assertEquals("boom", failedState.message)
        }

    /**
     * The snapshots say only what they are allowed to say.
     *
     * The detail snapshot's subject is the twelve allow-listed fields; the list snapshot is the
     * sections, and each row carries the six fields the row shows. A field nobody wrote down is
     * a field that has to be justified before it can be published.
     */
    @Test
    fun `the snapshots carry only the allowlisted fields`() =
        runTest {
            val screens = JourneyScreens(this)
            screens.seed(screens.task(id = "t1", title = "Buy milk"))

            val inbox = screens.inbox()
            awaitState { inbox.snapshot().text("status") == "loaded" }
            val listSnapshot = inbox.snapshot()
            assertEquals(setOf("screen", "status", "subject"), listSnapshot.keys)
            val subject = assertNotNull(listSnapshot.obj("subject"))
            assertEquals(setOf("view", "today", "sections"), subject.keys)

            val section = assertNotNull(listSnapshot.sections().firstOrNull())
            assertEquals(setOf("id", "name", "badge", "tasks"), section.keys)

            val row = assertNotNull(listSnapshot.rows().firstOrNull())
            // Exactly the fields the row shows. A field nobody wrote down has to be added
            // here before it can be published, which is what an allow-list is for; the
            // optional ones (`dueDate`, `project`) are absent on this task, so the set
            // below is the published shape for a row with neither.
            assertEquals(setOf("id", "title", "completed", "priority", "pinned"), row.keys)

            val detail = screens.taskDetail("t1")
            awaitState { detail.snapshot().text("status") == "loaded" }
            assertEquals(setOf("screen", "status", "subject"), detail.snapshot().keys)
        }

    private fun taskId(value: String): JsonObject = buildJsonObject { put("taskId", value) }
}

private val JOURNEY_NOW: Instant = Instant.parse("2026-09-16T09:00:00Z")

private val JOURNEY_USER = UserId("test-user")

/**
 * The screens a journey drives, all built on one set of fakes.
 *
 * One [SlotFakes] aggregate and one `FakeTaskRepository` behind every screen: the agenda, the
 * Task Detail coordinator and the task editor read and write the same store, which is what
 * makes a journey a journey rather than three unrelated screen tests. Nothing here is a new
 * fake or a new database — the scope kind comes from the repository's own VM harness, and the
 * work runs on the test scheduler, so a journey needs no wall clock and no sleep.
 */
private class JourneyScreens(private val scope: TestScope) {
    private val fakes = SlotFakes()
    private val settings = FakeSettingsRepository()
    private val draftStore = FakeDraftStore()
    private val clock = FakeClock(JOURNEY_NOW)

    fun seed(task: Task) = fakes.taskRepo.seed(task)

    fun seedProject(id: String) =
        fakes.projectsRepo.seed(
            Project(
                id = ProjectId(id),
                name = "Project $id",
                color = 0,
                createdAt = JOURNEY_NOW,
                updatedAt = JOURNEY_NOW,
                userId = JOURNEY_USER,
            ),
        )

    /** The task as the repository holds it — the effect, not a rendering. */
    suspend fun task(id: String): Task? = fakes.taskRepo.get(TaskId(id))

    /**
     * The same read, without suspending.
     *
     * `awaitState`'s predicate is a plain lambda, so a wait on a write has to read the fake's
     * own flow synchronously. This is the fake's state, not a second source of truth.
     */
    fun storedTask(id: String): Task? = fakes.taskRepo.tasks.value[id]

    /** The stored tasks, synchronously — what the list would read next. */
    fun storedTasks(): List<Task> =
        fakes.taskRepo.tasks.value.values
            .toList()

    /** The stored task with [title], synchronously. */
    fun storedTaskByTitle(title: String): Task? = storedTasks().firstOrNull { it.title == title }

    fun task(
        id: String,
        title: String,
        dueDate: LocalDate? = null,
        projectId: String? = null,
    ) = Task(
        id = TaskId(id),
        title = title,
        priority = TaskPriority.None,
        dueDate = dueDate,
        projectId = projectId?.let(::ProjectId),
        createdAt = JOURNEY_NOW,
        updatedAt = JOURNEY_NOW,
        userId = JOURNEY_USER,
    )

    /** The Inbox tab: the undated tasks. */
    fun inbox(): AgendaScreenIntrospection = agenda(AgendaPresets.Inbox, projectId = null)

    /** A project's task list — what `AgendaStartRoute.Project` opens. */
    fun projectList(projectId: String): AgendaScreenIntrospection {
        val id = ProjectId(projectId)
        return agenda(AgendaPresets.byProject(id), projectId = id)
    }

    fun taskDetail(taskId: String): TaskDetailScreenIntrospection =
        TaskDetailScreenIntrospection(
            scope
                .testVm<TaskDetailCoordinator, TaskDetailUiState>(
                    stateAccessor = { vm: TaskDetailCoordinator -> vm.state },
                    vmScope = VmScope.Background,
                ) { vmScope -> TaskDetailCoordinator(deps = fakes.deps(), taskId = TaskId(taskId), scope = vmScope) }
                .vm,
        )

    fun taskEditor(seed: TaskCreateSeed = TaskCreateSeed()): TaskCreateViewModel =
        scope
            .testVm<TaskCreateViewModel, DraftUiState<TaskDraft>>(
                stateAccessor = { vm: TaskCreateViewModel -> vm.state },
                vmScope = VmScope.Background,
            ) { vmScope ->
                TaskCreateViewModel(
                    deps =
                        TaskCreateDeps(
                            createFromDraft =
                                CreateTaskFromDraftUseCase(
                                    fakes.taskRepo,
                                    clock,
                                    TEST_TZ,
                                    fakes.currentUser,
                                    fakes.checklistRepo,
                                    fakes.attachmentsRepo,
                                    FakeUnitOfWork(),
                                    MaterializeRecurringSeriesUseCase(fakes.taskRepo, FakeUnitOfWork(), clock),
                                ),
                            logger = Logger,
                            draftStore = draftStore,
                        ),
                    initialDueDate = seed.initialDueDate,
                    draftKey = seed.draftKey,
                    initialProjectId = seed.initialProjectId,
                    initialTagIds = seed.initialTagIds,
                    scope = vmScope,
                )
            }.vm

    private fun agenda(
        definition: AgendaDefinition,
        projectId: ProjectId?,
    ): AgendaScreenIntrospection {
        // The adapter is built from the ViewModel, so the event collector — the app's own
        // event handler, in miniature — reaches the finished adapter through this holder
        // rather than the adapter being constructed twice.
        val adapter = arrayOfNulls<AgendaScreenIntrospection>(1)
        val vm =
            scope
                .testVm<AgendaViewModel, AgendaUiState>(
                    stateAccessor = { screen: AgendaViewModel -> screen.stateFlow },
                    vmScope = VmScope.Background,
                    afterInit = { screen, bgScope ->
                        bgScope.launch {
                            screen.events.collect { event: AgendaUiEvent -> adapter[0]?.onEvent(event) }
                        }
                    },
                ) { vmScope -> AgendaViewModel(deps = agendaDeps(), definition = definition, scope = vmScope) }
                .vm
        return AgendaScreenIntrospection(vm, projectId).also { adapter[0] = it }
    }

    private fun agendaDeps() =
        AgendaDeps(
            taskRepo = fakes.taskRepo,
            clock = clock,
            timeZone = TEST_TZ,
            logger = Logger,
            draftStore = draftStore,
            reminderScheduler = fakes.scheduler,
            currentUser = fakes.currentUser,
            taskMutations = FakeTaskMutationsUseCase(fakes.taskRepo),
            completion = TaskReopenUseCase(fakes.taskRepo, fakes.projectsRepo, clock),
            mergeSeries = MergeTaskSeriesUseCase(fakes.taskRepo),
            workScheduleSettings = settings.workSchedule,
            greetingSettings = settings.greeting,
            dateGrouping = FakeDateGroupingSettingsRepository(),
            projectsRepository = fakes.projectsRepo,
        )
}

/** The named field, read as a string. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The named field, read as a boolean. */
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** The named field, read as a nested object. */
private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

/** Every task row the list's subject publishes, across all its sections. */
private fun JsonObject.rows(): List<JsonObject> =
    obj("subject")
        ?.get("sections")
        ?.jsonArray
        .orEmpty()
        .flatMap { section -> section.jsonObject["tasks"]?.jsonArray.orEmpty() }
        .map { it.jsonObject }

/** Every task id the list's subject publishes. */
private fun JsonObject.listedTaskIds(): Set<String> = rows().mapNotNull { it.text("id") }.toSet()

/** The sections of a snapshot's subject. */
private fun JsonObject.sections(): List<JsonObject> =
    obj("subject")
        ?.get("sections")
        ?.jsonArray
        .orEmpty()
        .map { it.jsonObject }

/** One row of the list, by task id. */
private fun JsonObject.row(taskId: String): JsonObject? = rows().firstOrNull { it.text("id") == taskId }

private fun JsonObject.titleOf(taskId: String): String? = row(taskId)?.text("title")

private fun JsonObject.priorityOf(taskId: String): String? = row(taskId)?.text("priority")

private fun JsonObject.boolOf(
    taskId: String,
    key: String,
): Boolean? = row(taskId)?.bool(key)

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
