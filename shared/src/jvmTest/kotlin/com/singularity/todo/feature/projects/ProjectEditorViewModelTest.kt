
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.ProjectsDomain
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.state.ProjectEditorIntent
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.FakePreferencesDataStore
import com.singularity.todo.feature.projects.presentation.RecentEmojiStore
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Smoke tests for [ProjectEditorViewModel] — verify construction and basic intent flow.
 * Detailed save/load tests are out of scope for MR3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ProjectEditorViewModelTest {
    private val testUserId = UserId("test-user")
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val fakeCurrentUser =
        FakeProfileAwareCurrentUser(
            FakeAuthRepository(initialSession = Session.Anonymous(testUserId)),
        )

    private fun createVm(
        scope: CoroutineScope,
        projectId: ProjectId? = null,
        recentEmojiStore: RecentEmojiStore = newRecentEmojiStore(scope),
    ) = ProjectEditorViewModel(
        projectId = projectId,
        createProject = CreateProjectUseCase(fakeProjectsRepo, Clock.System, fakeCurrentUser),
        updateProject = UpdateProjectUseCase(fakeProjectsRepo, Clock.System),
        projectsRepo = fakeProjectsRepo,
        recentEmojiStore = recentEmojiStore,
        scope = testScope(scope),
    )

    /**
     * A store over a store that answers from memory.
     *
     * The scope is the test's own, so a write lands when the test says so
     * rather than whenever the disk feels like it.
     */
    private fun newRecentEmojiStore(
        scope: CoroutineScope,
        dataStore: FakePreferencesDataStore = FakePreferencesDataStore(),
    ): RecentEmojiStore = RecentEmojiStore(dataStore = dataStore, scope = scope)

    @Test
    fun `initial state has empty name for new project`() =
        runTest {
            val vm = createVm(backgroundScope)
            assertEquals("", vm.stateFlow.value.name.text)
        }

    @Test
    fun `NameChanged updates state`() =
        runTest {
            val vm = createVm(backgroundScope)
            vm.onIntent(ProjectEditorIntent.NameChanged("My project"))
            assertEquals("My project", vm.stateFlow.value.name.text)
        }

    // ─── W4: the editor must not keep a private copy of the name rule ──────────

    @Test
    fun `save accepts a name of exactly max length`() =
        runTest {
            val vm = createVm(backgroundScope)
            vm.onIntent(ProjectEditorIntent.NameChanged("a".repeat(ProjectsDomain.MAX_NAME_LENGTH)))
            vm.onIntent(ProjectEditorIntent.Save)

            assertEquals(null, vm.stateFlow.value.errorMessage)
            assertEquals(1, fakeProjectsRepo.observeAll().first().size)
        }

    @Test
    fun `save rejects a name one character past the limit`() =
        runTest {
            val vm = createVm(backgroundScope)
            vm.onIntent(ProjectEditorIntent.NameChanged("a".repeat(ProjectsDomain.MAX_NAME_LENGTH + 1)))
            vm.onIntent(ProjectEditorIntent.Save)

            assertNotNull(vm.stateFlow.value.errorMessage)
            assertTrue(fakeProjectsRepo.observeAll().first().isEmpty())
        }

    @Test
    fun `save accepts 50 emoji that measure 100 utf-16 units`() =
        runTest {
            // The case the old private `name.length > 50` check rejected: 50 visible
            // characters, 100 UTF-16 units, under a limit the message states in characters.
            val name = "🎉".repeat(ProjectsDomain.MAX_NAME_LENGTH)
            assertEquals(100, name.length, "precondition: 100 UTF-16 units")

            val vm = createVm(backgroundScope)
            vm.onIntent(ProjectEditorIntent.NameChanged(name))
            vm.onIntent(ProjectEditorIntent.Save)

            assertEquals(null, vm.stateFlow.value.errorMessage)
            assertEquals(1, fakeProjectsRepo.observeAll().first().size)
        }

    @Test
    fun `editor and domain agree on the error for an over-long name`() =
        runTest {
            val name = "🎉".repeat(ProjectsDomain.MAX_NAME_LENGTH + 1)
            val domainError = ProjectsDomain.validateName(name)

            val vm = createVm(backgroundScope)
            vm.onIntent(ProjectEditorIntent.NameChanged(name))
            vm.onIntent(ProjectEditorIntent.Save)

            assertEquals(domainError?.message, vm.stateFlow.value.errorMessage)
        }

    // ─── The recently used emoji ──────────────────────────────────────────────

    @Test
    fun `a picked emoji is remembered`() =
        runTest {
            val store = newRecentEmojiStore(backgroundScope)
            val vm = createVm(backgroundScope, recentEmojiStore = store)

            vm.onIntent(ProjectEditorIntent.IconChanged(Emoji("🎯")))
            runCurrent()

            assertEquals(listOf("🎯"), store.recent.first(), "the picked emoji is the newest one")
        }

    @Test
    fun `a registry icon is not remembered`() =
        runTest {
            val store = newRecentEmojiStore(backgroundScope)
            val vm = createVm(backgroundScope, recentEmojiStore = store)

            vm.onIntent(ProjectEditorIntent.IconChanged(Emoji("Home")))
            runCurrent()

            assertEquals(emptyList(), store.recent.first(), "a registry icon is not an emoji")
        }

    @Test
    fun `no icon at all is not remembered`() =
        runTest {
            val store = newRecentEmojiStore(backgroundScope)
            val vm = createVm(backgroundScope, recentEmojiStore = store)

            vm.onIntent(ProjectEditorIntent.IconChanged(null))
            runCurrent()

            assertEquals(emptyList(), store.recent.first(), "picking nothing is not a pick")
        }

    @Test
    fun `a failing write does not keep the icon from being applied`() =
        runTest {
            val store =
                newRecentEmojiStore(
                    backgroundScope,
                    FakePreferencesDataStore(onUpdate = { throw IOException("the disk is full") }),
                )
            val vm = createVm(backgroundScope, recentEmojiStore = store)

            vm.onIntent(ProjectEditorIntent.IconChanged(Emoji("🎯")))
            runCurrent()

            assertEquals(Emoji("🎯"), vm.stateFlow.value.icon, "the icon was applied anyway")
        }

    @Test
    fun `a slow write does not keep the icon from being applied`() =
        runTest {
            val store =
                newRecentEmojiStore(
                    backgroundScope,
                    FakePreferencesDataStore(onUpdate = {
                        delay(5_000)
                        it
                    }),
                )
            val vm = createVm(backgroundScope, recentEmojiStore = store)

            vm.onIntent(ProjectEditorIntent.IconChanged(Emoji("🎯")))

            // No waiting: the pick is applied before the write even starts.
            assertEquals(Emoji("🎯"), vm.stateFlow.value.icon, "the icon was applied without the write")
        }
}
