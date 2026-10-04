@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tags

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val FIXED_NOW = Instant.fromEpochMilliseconds(1_700_000_000_000)

private class FixedClock : Clock {
    override fun now(): Instant = FIXED_NOW
}

private fun tag(id: String, name: String, color: Int = 0xFFE91E63.toInt()) = Tag(
    id = TagId.fromString(id),
    name = name,
    color = color,
    createdAt = FIXED_NOW,
    updatedAt = FIXED_NOW,
    userId = TestUsers.DEFAULT,
)

/**
 * The ViewModel plus the scope it collects on.
 *
 * `TagsViewModel.init` collects `observeAll()` forever, so the scope is built
 * on the **foreground** [TestScope] context under a child [Job] and closed
 * explicitly at the end of each test.
 *
 * Why not `backgroundScope`: `advanceUntilIdle()` drains the queue only while
 * **foreground** work is pending — coroutines queued on `backgroundScope` run
 * only as a side effect of that pump. A ViewModel collecting on
 * `backgroundScope` therefore never emits its first state here (the test body
 * is otherwise idle), every assertion reads `Loading`, and — the dangerous
 * part — rejection tests keep passing against an implementation that does
 * nothing. `TestScopeSemanticsTest` in `com.singularity.todo.test` pins this
 * behaviour; the alternative working shape is a helper that suspends until the
 * state matches, which lets `runTest` pump the background work.
 */
private class TagsHarness(val vm: TagsViewModel, private val scope: AutoCloseableCoroutineScope) : AutoCloseable {
    override fun close() = scope.close()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.newHarness(repo: FakeTagsRepository): TagsHarness {
    // The canonical helper does exactly this shape: foreground context under a
    // child Job, so close() cancels the VM's collectors without taking runTest
    // down with them.
    val scope = testScope(this)
    val vm = TagsViewModel(
        tagRepo = repo,
        createTag = CreateTagUseCase(repo, FixedClock()),
        updateTag = UpdateTagUseCase(repo, FixedClock()),
        currentUser = FakeProfileAwareCurrentUser(authRepository = FakeAuthRepository()),
        scope = scope,
    )
    return TagsHarness(vm, scope)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class TagRenameTest {

    // ─── The id must survive a rename, or every task link breaks ───────────

    @Test
    fun `rename keeps the same id`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-1", "work")
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(original.id, "office", original.color))
        advanceUntilIdle()

        val renamed = assertNotNull(repo.get(original.id))
        assertEquals("office", renamed.name)
        assertEquals(original.id, renamed.id)
        h.close()
    }

    @Test
    fun `rename preserves createdAt and stamps updatedAt`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-2", "work", color = 0xFF2196F3.toInt())
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(original.id, "renamed", original.color))
        advanceUntilIdle()

        val renamed = assertNotNull(repo.get(original.id))
        assertEquals(original.createdAt, renamed.createdAt)
        assertEquals(FIXED_NOW, renamed.updatedAt)
        h.close()
    }

    @Test
    fun `rename trims surrounding whitespace`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-3", "work")
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(original.id, "  spaced  ", original.color))
        advanceUntilIdle()

        assertEquals("spaced", assertNotNull(repo.get(original.id)).name)
        h.close()
    }

    @Test
    fun `rename is reflected in the observed list`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-4", "before")
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(original.id, "after", original.color))
        advanceUntilIdle()

        val state = assertIs<TagsUiState.Content>(h.vm.state.value)
        assertEquals("after", state.tags.single().name)
        h.close()
    }

    // ─── Rejections — a rename must not be a way around the write rules ───
    //
    // Each of these asserts the *stored* value is still the original, which a
    // rename that silently did nothing would also satisfy. They are only
    // meaningful because the four success tests above prove the same code path
    // does write when the input is valid.

    @Test
    fun `rename rejects a blank name and leaves the tag untouched`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-5", "work")
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(original.id, "   ", original.color))
        advanceUntilIdle()

        assertEquals("work", assertNotNull(repo.get(original.id)).name)
        h.close()
    }

    @Test
    fun `rename rejects a name longer than 100 characters`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-6", "work")
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(original.id, "x".repeat(101), original.color))
        advanceUntilIdle()

        assertEquals("work", assertNotNull(repo.get(original.id)).name)
        h.close()
    }

    @Test
    fun `rename accepts a name of exactly 100 characters`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-7", "work")
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        val name = "x".repeat(100)
        h.vm.onIntent(TagsIntent.Rename(original.id, name, original.color))
        advanceUntilIdle()

        assertEquals(name, assertNotNull(repo.get(original.id)).name)
        h.close()
    }

    @Test
    fun `rename rejects a transparent color`() = runTest {
        val repo = FakeTagsRepository()
        val original = tag("tg-8", "work")
        repo.seed(original)
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(original.id, "renamed", 0))
        advanceUntilIdle()

        val stored = assertNotNull(repo.get(original.id))
        assertEquals("work", stored.name)
        assertEquals(original.color, stored.color)
        h.close()
    }

    // ─── Failure paths surface as events, not crashes ───────────────────────

    @Test
    fun `renaming a deleted tag reports an error and does not recreate it`() = runTest {
        val repo = FakeTagsRepository()
        val h = newHarness(repo)
        advanceUntilIdle()
        val errors = mutableListOf<TagsUiEvent>()
        // `launch` on the TestScope itself is foreground work — advanced by
        // advanceUntilIdle; runCurrent would work too.
        val collector = launch { h.vm.events.toList(errors) }
        advanceUntilIdle()

        h.vm.onIntent(TagsIntent.Rename(TagId.fromString("tg-gone"), "ghost", 0xFF00FF00.toInt()))
        advanceUntilIdle()

        assertTrue(repo.get(TagId.fromString("tg-gone")) == null, "a missing tag must not be recreated")
        assertEquals(1, errors.size, "a missing tag must surface as ShowError, not a thrown exception")
        assertIs<TagsUiEvent.ShowError>(errors.single())
        collector.cancel()
        h.close()
    }
}

// ─── The use case holds the rule, independent of the UI ─────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class UpdateTagUseCaseTest {

    private fun useCase(repo: FakeTagsRepository = FakeTagsRepository()) = UpdateTagUseCase(repo, FixedClock())

    @Test
    fun `rejects a blank name with a Validation error`() = runTest {
        val result = useCase()(tag("tg-a", "  "))
        assertTrue(result.isFailure)
        assertIs<AppError.Validation>(result.exceptionOrNull())
    }

    @Test
    fun `rejects a name over 100 characters with a Validation error`() = runTest {
        val result = useCase()(tag("tg-b", "x".repeat(101)))
        assertTrue(result.isFailure)
        assertIs<AppError.Validation>(result.exceptionOrNull())
    }

    @Test
    fun `rejects a transparent color with a Validation error`() = runTest {
        val result = useCase()(tag("tg-c", "named", color = 0))
        assertTrue(result.isFailure)
        assertIs<AppError.Validation>(result.exceptionOrNull())
    }

    @Test
    fun `reports the name error before the colour error`() = runTest {
        val result = useCase()(tag("tg-d", "", color = 0))
        val error = assertIs<AppError.Validation>(result.exceptionOrNull())
        assertTrue(assertNotNull(error.message).contains("blank"))
    }

    @Test
    fun `accepts a valid rename`() = runTest {
        val repo = FakeTagsRepository()
        repo.seed(tag("tg-e", "before"))
        val result = useCase(repo)(tag("tg-e", "after"))
        assertTrue(result.isSuccess)
        assertEquals("after", assertNotNull(repo.get(TagId.fromString("tg-e"))).name)
    }
}
