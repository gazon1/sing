@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tags

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val NOW = Instant.fromEpochMilliseconds(1_700_000_000_000)

private class FrozenClock : Clock {
    override fun now(): Instant = NOW
}

private fun aTag(
    id: String,
    name: String,
) = Tag(
    id = TagId.fromString(id),
    name = name,
    color = 0xFFE91E63.toInt(),
    createdAt = NOW,
    updatedAt = NOW,
    userId = TestUsers.DEFAULT,
)

/**
 * The ViewModel plus the scope it collects on.
 *
 * `TagsViewModel.init` collects `observeAll()` forever, so the scope is built on
 * the foreground [TestScope] under a child [Job] and closed explicitly. Building
 * it on `backgroundScope` instead would leave every assertion reading `Loading` —
 * see the longer note on the same harness in `TagRenameTest`.
 */
private class Harness(val vm: TagsViewModel, private val scope: AutoCloseableCoroutineScope) : AutoCloseable {
    override fun close() = scope.close()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.newHarness(
    repo: TagsRepository,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
): Harness {
    val scope = testScope(this)
    val vm =
        TagsViewModel(
            tagRepo = repo,
            createTag = CreateTagUseCase(repo, FrozenClock()),
            updateTag = UpdateTagUseCase(repo, FrozenClock()),
            currentUser = FakeProfileAwareCurrentUser(authRepository = FakeAuthRepository()),
            crashReporter = crashReporter,
            scope = scope,
        )
    return Harness(vm, scope)
}

/**
 * Delete reaches the repository — through the intent, because the screen used to
 * take `viewModel::delete` directly while `onCreate` and `onRename` went through
 * `onIntent`. That left `TagsIntent.Delete` routed and never dispatched, which is
 * also why this class is named for the ViewModel: `ViewModelTestCoverageTest`
 * lists `TagsViewModel` as uncovered purely because no class was *called*
 * `TagsViewModelTest`.
 *
 * A class named `TagRenameTest` does not satisfy that check, whatever it covers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TagsViewModelTest {
    @Test
    fun `Delete intent removes the tag`() =
        runTest {
            val repo = FakeTagsRepository()
            val tag = aTag("tg-del-1", "work")
            repo.seed(tag)
            val h = newHarness(repo)
            advanceUntilIdle()

            h.vm.onIntent(TagsIntent.Delete(tag.id))
            advanceUntilIdle()

            assertNotNull(
                assertNotNull(repo.get(tag.id)).deletedAt,
                "the delete must reach the repository, not just the observed list",
            )
            h.close()
        }

    @Test
    fun `a failed delete reports the failure to the user`() =
        runTest {
            val repo = FakeTagsRepository()
            val tag = aTag("tg-del-2", "work")
            repo.seed(tag)
            repo.deleteOverride = Result.failure(IllegalStateException("write failed"))
            val h = newHarness(repo)
            advanceUntilIdle()

            val events = mutableListOf<TagsUiEvent>()
            val collector = launch { h.vm.events.collect { events += it } }
            try {
                h.vm.onIntent(TagsIntent.Delete(tag.id))
                advanceUntilIdle()
                runCurrent()

                assertNull(
                    assertNotNull(repo.get(tag.id)).deletedAt,
                    "a rejected delete must not mark the tag deleted",
                )
                assertTrue(
                    events.any { it is TagsUiEvent.ShowError },
                    "the user must be told the delete failed: $events",
                )
            } finally {
                collector.cancel()
                h.close()
            }
        }

    @Test
    fun `Create and Rename still dispatch through their intents`() =
        runTest {
            val repo = FakeTagsRepository()
            val h = newHarness(repo)
            advanceUntilIdle()

            h.vm.onIntent(TagsIntent.Create("fresh", 0xFF2196F3.toInt()))
            advanceUntilIdle()

            // Read the ViewModel's own state — the same surface the screen renders.
            val content = assertIs<TagsUiState.Content>(h.vm.stateFlow.value)
            val created = content.tags.single()
            assertEquals("fresh", created.name)

            h.vm.onIntent(TagsIntent.Rename(created.id, "renamed", created.color))
            advanceUntilIdle()

            val renamed = assertIs<TagsUiState.Content>(h.vm.stateFlow.value).tags.single()
            assertEquals("renamed", renamed.name)
            assertEquals(created.id, renamed.id, "a rename must not change the id")
            h.close()
        }

    /**
     * A stopped observation must hand the *cause* to the reporter.
     *
     * The screen can only show a message; the message alone says nothing about
     * why the list stopped, and the throwable that explains it is the thing the
     * reporter exists for. So the report has to carry the original throwable —
     * the very instance the repository threw, not a copy of it — under this
     * screen's own key.
     */
    @Test
    fun `a failed observation reports the original cause`() =
        runTest {
            val reporter = RecordingCrashReportingPort()
            val repo = FakeTagsRepository()
            val failure = IllegalStateException("the tag repository is down")
            repo.observeAllFailure = failure
            val h = newHarness(repo, reporter)
            advanceUntilIdle()

            assertIs<TagsUiState.Error>(h.vm.stateFlow.value)

            val reported = reporter.reports.single { it.second == "tags.observe_failed" }
            assertSame(
                failure,
                reported.first,
                "the reporter must receive the throwable the repository threw",
            )
            h.close()
        }
}
