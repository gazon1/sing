@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.state.TaskCreateIntent
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
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.helpers.VmScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import com.singularity.todo.test.helpers.testVm as harnessTestVm

/**
 * The tag scope of the draft.
 *
 * The draft key carries the tags the editor was opened with, so a draft written
 * under one set of tags must not open in an editor opened under another set —
 * the task would land in tags the person never picked.
 *
 * The rows are pure Kotlin: the editor, the draft store and the use case are all
 * common code, so the class lives in `commonTest` and runs on every tier.
 */
@Tag("fast")
class TaskCreateTagScopeTest {
    /**
     * A fixed instant, so a draft that resolves `DueDateOption.Today` gets a date
     * this file can state.
     */
    private val taskNow: Instant = Instant.parse("2026-09-16T09:00:00Z")

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val draftStore = FakeDraftStore()
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
     * The real use case creates the task, so the rows can watch where the
     * restored draft's tags end up: a restore that kept the title but dropped the
     * associations would show the title and still create a task without the tags
     * the person picked.
     *
     * The scope comes from the shared harness ([harnessTestVm]) with
     * [VmScope.Background]: the autosave collector never ends, so it belongs in
     * the background scope — the same arrangement the other draft-editor tests
     * use.
     */
    private fun TestScope.testVm(initialTagIds: Set<TagId>): TaskCreateViewModel =
        harnessTestVm(
            stateAccessor = { vm: TaskCreateViewModel -> vm.state },
            vmScope = VmScope.Background,
        ) { scope ->
            TaskCreateViewModel(
                deps =
                    TaskCreateDeps(
                        createFromDraft =
                            CreateTaskFromDraftUseCase(
                                fakeTaskRepo,
                                FakeClock(taskNow),
                                TEST_TZ,
                                fakeCurrentUser,
                                fakeChecklistRepository,
                                fakeAttachmentRepository,
                                FakeUnitOfWork(),
                                MaterializeRecurringSeriesUseCase(fakeTaskRepo, FakeUnitOfWork(), FakeClock(taskNow)),
                            ),
                        logger = Logger.withTag("TaskCreateTagScopeTest"),
                        draftStore = draftStore,
                    ),
                initialDueDate = null,
                draftKey = null,
                initialTagIds = initialTagIds,
                scope = scope,
            )
        }.vm

    /**
     * The negative half of the guarantee: a draft saved under one set of tags
     * does not open in an editor opened under another set.
     */
    @Test
    fun `a draft is restored only into the tag scope it was saved in`() =
        runTest {
            val draft =
                TaskDraft(
                    title = FieldOwnedText.empty().seeded("Tagged draft"),
                    tagIds = listOf(TagId("tag-1").value),
                )
            draftStore.save(taskDraftStorageKey(null, null, setOf(TagId("tag-1"))), draft, TaskDraft.serializer())

            val vm = testVm(initialTagIds = setOf(TagId("tag-2")))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                "",
                vm.state.value.draft.title.text,
                "a draft from another tag scope must not leak into this one",
            )
            assertEquals(
                listOf(TagId("tag-2").value),
                vm.state.value.draft.tagIds,
                "the draft keeps the tags the editor was opened with, not the other scope's",
            )

            // The editor opened under another tag set starts empty, so the save is
            // refused for the missing title — the other tag scope's draft did not
            // leak in.
            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals("Title is required", vm.state.value.error)
            assertEquals(0, fakeTaskRepo.tasks.value.size, "no task was created out of another tag scope's draft")
        }

    /**
     * The positive half of the guarantee: the draft saved for a set of tags is
     * restored when the editor opens under *that* set. The order of the tags does
     * not matter — the key sorts them — so the draft is found even when the
     * editor was opened with the tags in another order.
     */
    @Test
    fun `a draft is restored into the tag scope it was saved in`() =
        runTest {
            val draft =
                TaskDraft(
                    title = FieldOwnedText.empty().seeded("Tagged draft"),
                    tagIds = listOf(TagId("tag-1").value, TagId("tag-2").value),
                )
            draftStore.save(
                taskDraftStorageKey(null, null, setOf(TagId("tag-2"), TagId("tag-1"))),
                draft,
                TaskDraft.serializer(),
            )

            val vm = testVm(initialTagIds = setOf(TagId("tag-1"), TagId("tag-2")))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                "Tagged draft",
                vm.state.value.draft.title.text,
                "the draft saved for this tag scope is restored into it",
            )
            // The restored draft is the whole draft: the tags it was written with
            // come back with it. A restore that kept only the title would show the
            // title and still create the task without the tags the person picked.
            assertEquals(
                listOf(TagId("tag-1").value, TagId("tag-2").value),
                vm.state.value.draft.tagIds,
                "the restored draft keeps the tags it was written under",
            )

            vm.onIntent(TaskCreateIntent.SaveClicked)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, fakeTaskRepo.tasks.value.size, "the task is created from the restored draft")
            val created =
                fakeTaskRepo.tasks.value.values
                    .first()
            assertEquals(
                "Tagged draft",
                created.title,
                "the task is created from the restored draft",
            )
            // The tags do not stop at the draft: they have to reach the task, or
            // the scoping guarantee only holds until the save.
            assertEquals(
                listOf(TagId("tag-1"), TagId("tag-2")),
                created.tags,
                "the task lands in the tags the draft was written under",
            )
        }
}
