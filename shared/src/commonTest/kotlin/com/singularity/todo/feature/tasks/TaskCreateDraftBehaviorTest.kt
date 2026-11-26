@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.domain.state.TaskCreateUiEvent
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.feature.tasks.presentation.viewmodel.taskDraftStorageKey
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeFailingDraftStore
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.helpers.VmScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.singularity.todo.test.helpers.testVm as harnessTestVm

/**
 * The rows of the behavior matrix that belong to the create editor.
 *
 * The base class owns the contract; the create editor overrides three extension
 * points — `validate`, `onSaved` and `onAutosaveError` — and each override has
 * to keep its promise. The rows live here, in `commonTest`, because the editor,
 * the draft store and the use case they exercise are common code: the rows then
 * run on every tier, not only on the JVM one.
 *
 * The tag half of the scoping guarantee lives in `TaskCreateTagScopeTest`.
 */
@Tag("fast")
class TaskCreateDraftBehaviorTest {
    /**
     * A fixed instant, so a draft that resolves `DueDateOption.Today` gets a date
     * this file can state.
     */
    private val taskNow: Instant = Instant.parse("2026-09-16T09:00:00Z")

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeDraftStore = FakeDraftStore()
    private val fakeCurrentUser =
        FakeProfileAwareCurrentUser(
            FakeAuthRepository(
                initialSession = Session.Anonymous(testUserId),
            ),
        )
    private val fakeChecklistRepository = FakeChecklistRepository()

    // The same user as the task's, so `observeAll` sees what the save wrote.
    private val fakeAttachmentRepository = FakeAttachmentRepository(currentUser = fakeCurrentUser)

    /**
     * The editor under test.
     *
     * The scope comes from the shared harness ([harnessTestVm]) with
     * [VmScope.Background]: the autosave collector never ends, so it belongs in
     * the background scope.
     */
    private fun TestScope.createVm(
        initialProjectId: ProjectId? = null,
        draftStore: DraftStore = fakeDraftStore,
        taskRepository: TaskRepository = fakeTaskRepo,
    ): TaskCreateViewModel =
        harnessTestVm(
            stateAccessor = { vm: TaskCreateViewModel -> vm.state },
            vmScope = VmScope.Background,
        ) { scope ->
            TaskCreateViewModel(
                deps =
                    TaskCreateDeps(
                        createFromDraft =
                            CreateTaskFromDraftUseCase(
                                taskRepository,
                                FakeClock(taskNow),
                                TEST_TZ,
                                fakeCurrentUser,
                                fakeChecklistRepository,
                                fakeAttachmentRepository,
                                FakeUnitOfWork(),
                                MaterializeRecurringSeriesUseCase(taskRepository, FakeUnitOfWork(), FakeClock(taskNow)),
                            ),
                        logger = Logger.withTag("TaskCreateDraftBehaviorTest"),
                        draftStore = draftStore,
                    ),
                initialDueDate = null,
                draftKey = null,
                initialProjectId = initialProjectId,
                initialTagIds = emptySet(),
                scope = scope,
            )
        }.vm

    /**
     * The screen navigates away on the event, not on the state: the task is
     * created, but without the event the editor stays open over a task that
     * already exists.
     */
    @Test
    fun `a successful save emits the Saved event`() =
        runTest {
            val vm = createVm()
            advanceTimeBy(1_000)
            runCurrent()

            val events = mutableListOf<TaskCreateUiEvent>()
            val job = launch { vm.events.collect { events += it } }
            runCurrent()

            vm.onIntent(TaskCreateIntent.TitleChanged("Emit me"))
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                1,
                events.filterIsInstance<TaskCreateUiEvent.Saved>().size,
                "the screen is told that the task exists",
            )
            job.cancel()
        }

    /**
     * The other half of the row above: a save that fails must not tell the
     * screen that the task exists.
     *
     * The base class calls `onSaved()` only when the persistence succeeded, and
     * the create editor's `onSaved()` is what emits `TaskCreateUiEvent.Saved` —
     * the event the screen navigates on. A failure that still emitted it would
     * close the editor over a task that was never created, so the editor has to
     * stay open, keep the text and show the error instead.
     */
    @Test
    fun `a failed save emits no Saved event`() =
        runTest {
            val vm = createVm(taskRepository = FailingTaskRepository())
            advanceTimeBy(1_000)
            runCurrent()

            val events = mutableListOf<TaskCreateUiEvent>()
            val job = launch { vm.events.collect { events += it } }
            runCurrent()

            vm.onIntent(TaskCreateIntent.TitleChanged("Never created"))
            runCurrent()
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                0,
                events.filterIsInstance<TaskCreateUiEvent.Saved>().size,
                "the screen is not told that a task exists when the save failed: $events",
            )
            assertNotNull(vm.state.value.error, "the failure reaches the screen")
            assertTrue(vm.state.value.isDirty, "a failed save is not a checkpoint")
            assertEquals(
                "Never created",
                vm.state.value.draft.title.text,
                "the text the person typed is still theirs",
            )
            job.cancel()
        }

    /**
     * A saved task is not a draft anymore: the persisted copy has to go, or the
     * next editor opens the text of a task that already exists.
     *
     * The key is the scope's own key — the same one the editor writes under —
     * so a store that clears a different key would not satisfy this row.
     */
    @Test
    fun `a successful save clears the persisted draft`() =
        runTest {
            val projectId = ProjectId("project-1")
            val vm = createVm(initialProjectId = projectId)
            advanceTimeBy(1_000)
            runCurrent()

            val scopedKey = taskDraftStorageKey(null, projectId, emptySet())
            vm.onIntent(TaskCreateIntent.TitleChanged("Persisted"))
            advanceTimeBy(1_000)
            runCurrent()
            assertNotNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the autosave wrote the draft under the scope's key",
            )

            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the saved draft is cleared from the store, so the next editor starts empty",
            )
        }

    /**
     * The create editor's discard clears the persisted drafts: the text of a
     * task the person threw away must not come back in the next editor.
     *
     * The branch does not roll the in-memory draft back to the last checkpoint —
     * the base class's `discard()` is not called from it. That divergence is
     * the parent's to decide (#3225), so this row pins only the half that is
     * the editor's own promise.
     */
    @Test
    fun `discard clears the persisted draft`() =
        runTest {
            val projectId = ProjectId("project-1")
            val vm = createVm(initialProjectId = projectId)
            advanceTimeBy(1_000)
            runCurrent()

            val scopedKey = taskDraftStorageKey(null, projectId, emptySet())
            vm.onIntent(TaskCreateIntent.TitleChanged("Thrown away"))
            advanceTimeBy(1_000)
            runCurrent()
            assertNotNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the autosave wrote the draft under the scope's key",
            )

            vm.onIntent(TaskCreateIntent.Discard)
            advanceTimeBy(1_000)
            runCurrent()

            assertNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the discarded draft is gone from the store",
            )
        }

    /**
     * The cleanup is not final while an autosave is still pending.
     *
     * The autosave is debounced, and the save does not cancel the pending
     * write: the debounce fires after the cleanup and puts the draft back into
     * the store. The row pins that order — the cleanup happens, and then the
     * pending write lands on top of it — because a draft that comes back after
     * the task was created is a real gap, and the parent issue (#3225) owns the
     * decision about it. What is *not* pinned here is the in-memory draft: the
     * row is about what the store holds.
     */
    @Test
    fun `a pending autosave rewrites the draft after the save`() =
        runTest {
            val projectId = ProjectId("project-1")
            val vm = createVm(initialProjectId = projectId)
            advanceTimeBy(1_000)
            runCurrent()

            val scopedKey = taskDraftStorageKey(null, projectId, emptySet())
            vm.onIntent(TaskCreateIntent.TitleChanged("Stored"))
            advanceTimeBy(1_000)
            runCurrent()
            assertNotNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the autosave stored the draft under the scope's key",
            )

            // The next edit starts a new debounce, which the save below does
            // not cancel: the write is still pending when the save runs.
            vm.onIntent(TaskCreateIntent.TitleChanged("Pending"))
            runCurrent()

            vm.onIntent(TaskCreateIntent.SaveClicked)
            runCurrent()
            assertNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the save cleared the persisted draft",
            )

            advanceTimeBy(1_000)
            runCurrent()
            assertNotNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the pending autosave wrote the draft again, after the save cleared it",
            )
        }

    /**
     * The same window, seen from the discard intent: the branch clears the
     * persisted drafts and does not cancel the pending autosave either, so the
     * pending write puts the thrown-away text back into the store.
     *
     * Like the row above, this pins the order that exists today and leaves the
     * decision about it to the parent issue (#3225).
     */
    @Test
    fun `a pending autosave rewrites the draft after the discard`() =
        runTest {
            val projectId = ProjectId("project-1")
            val vm = createVm(initialProjectId = projectId)
            advanceTimeBy(1_000)
            runCurrent()

            val scopedKey = taskDraftStorageKey(null, projectId, emptySet())
            vm.onIntent(TaskCreateIntent.TitleChanged("Stored"))
            advanceTimeBy(1_000)
            runCurrent()
            assertNotNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the autosave stored the draft under the scope's key",
            )

            vm.onIntent(TaskCreateIntent.TitleChanged("Pending"))
            runCurrent()

            vm.onIntent(TaskCreateIntent.Discard)
            runCurrent()
            assertNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the discard cleared the persisted draft",
            )

            advanceTimeBy(1_000)
            runCurrent()
            assertNotNull(
                fakeDraftStore.load(scopedKey, TaskDraft.serializer()),
                "the pending autosave wrote the draft again, after the discard cleared it",
            )
        }

    /**
     * A draft is stored under the scope it was written in — a project, a set of
     * tags. A draft written in one project's agenda must not open in another
     * project's editor, or the task lands in a project the person never chose.
     */
    @Test
    fun `a draft is restored only into the scope it was saved in`() =
        runTest {
            fakeDraftStore.save(
                taskDraftStorageKey(null, ProjectId("project-1"), emptySet()),
                TaskDraft(
                    title = FieldOwnedText.empty().seeded("Scoped draft"),
                    projectId = ProjectId("project-1").value,
                ),
                TaskDraft.serializer(),
            )

            val vm = createVm(initialProjectId = ProjectId("project-2"))
            advanceTimeBy(1_000)
            runCurrent()

            // The editor opened in project-2 starts empty, so the save is refused
            // for the missing title — the other project's draft did not leak in.
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(0, fakeTaskRepo.tasks.value.size, "a draft from another scope must not leak into this one")
            assertEquals("Title is required", vm.state.value.error)
            assertEquals(
                ProjectId("project-2").value,
                vm.state.value.draft.projectId,
                "the draft keeps the scope the editor was opened in, not the other scope's",
            )
        }

    /**
     * The other half of the scoping guarantee: the draft saved for a scope is
     * restored when the editor opens *in that scope*. Without this half, the
     * test above would also pass if the store were never restored at all.
     */
    @Test
    fun `a draft is restored into the scope it was saved in`() =
        runTest {
            fakeDraftStore.save(
                taskDraftStorageKey(null, ProjectId("project-1"), emptySet()),
                TaskDraft(
                    title = FieldOwnedText.empty().seeded("Scoped draft"),
                    projectId = ProjectId("project-1").value,
                ),
                TaskDraft.serializer(),
            )

            val vm = createVm(initialProjectId = ProjectId("project-1"))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                "Scoped draft",
                vm.state.value.draft.title.text,
                "the draft saved for this scope is restored into it",
            )
            // The restored draft is the whole draft, not only its title: the
            // project the draft was written under comes back with it. A restore
            // that dropped the associations would show the title and still create
            // the task in the wrong project.
            assertEquals(
                ProjectId("project-1").value,
                vm.state.value.draft.projectId,
                "the restored draft keeps the project it was written under",
            )

            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, fakeTaskRepo.tasks.value.size)
            val created =
                fakeTaskRepo.tasks.value.values
                    .first()
            assertEquals(
                "Scoped draft",
                created.title,
                "the task is created from the restored draft",
            )
            assertEquals(
                ProjectId("project-1"),
                created.projectId,
                "the task lands in the project the draft was written under",
            )
        }

    /**
     * The autosave is a safety net. A store that refuses the write must not take
     * the editor with it: the text stays, and the task can still be created from
     * the editor's own copy.
     *
     * The failure is also *silent*: the create editor logs it and nothing else,
     * while the note editor surfaces it to the user as a `SaveFailed` event. That
     * divergence between the two editors is the parent's to decide (#3225), so
     * this row pins the half that is the editor's own promise — the editor stays
     * usable — and states the silence instead of leaving it implicit. Both halves
     * are asserted here, so the matrix names this one test for both of them
     * rather than claiming two rows of coverage for a single test.
     */
    @Test
    fun `a failed autosave leaves the editor usable`() =
        runTest {
            val store = FakeFailingDraftStore()
            val vm = createVm(draftStore = store)
            advanceTimeBy(1_000)
            runCurrent()

            val events = mutableListOf<TaskCreateUiEvent>()
            val job = launch { vm.events.collect { events += it } }
            runCurrent()

            vm.onIntent(TaskCreateIntent.TitleChanged("Survives"))
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(store.saveAttempts > 0, "the autosave really reached the broken store")
            assertEquals("Survives", vm.state.value.draft.title.text, "the text is the editor's, not the store's")
            assertTrue(vm.state.value.isDirty, "a failed autosave is not a checkpoint")
            assertTrue(
                events.isEmpty(),
                "a failed autosave is logged, not reported to the user: $events",
            )

            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, fakeTaskRepo.tasks.value.size, "the task is created from the editor's copy")
            job.cancel()
        }
}

/**
 * A repository whose writes fail, so the save takes the persistence-failure
 * branch of the base class instead of the success one.
 */
private class FailingTaskRepository : FakeTaskRepository() {
    override suspend fun createSeries(tasks: List<Task>): Result<List<Task>> =
        Result.failure(IllegalStateException("storage is full"))
}
