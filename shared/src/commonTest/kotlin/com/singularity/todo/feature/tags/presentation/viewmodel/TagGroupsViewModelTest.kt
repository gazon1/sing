package com.singularity.todo.feature.tags.presentation.viewmodel

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.model.UpdateTagGroupInput
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tags.domain.usecase.CreateTagGroupUseCase
import com.singularity.todo.feature.tags.domain.usecase.DeleteTagGroupUseCase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.jupiter.api.Tag

/**
 * The ViewModel's own state machine, which was untested while the repository
 * below it was covered by [TagGroupDeleteCascadeTest].
 *
 * What is worth asserting here is the mapping the VM performs, not the
 * persistence the repository already has a test for:
 * - empty list → [TagGroupsUiState.Empty], non-empty → [Content]
 * - a failing stream → [TagGroupsUiState.Error] rather than a silent stop
 * - `Create` / `Delete` reach the use case with the argument the intent carried
 */
@Tag("fast")
@OptIn(ExperimentalCoroutinesApi::class)
class TagGroupsViewModelTest {

    private val fakeRepo = FakeTagGroupRepository()

    /**
     * The VM collects `observeAll()` for as long as it lives, so its scope must
     * be cancelled at the end of each test or `runTest` waits out its full
     * one-minute timeout. This is the counterpart to using the TestScope's own
     * context: `backgroundScope` is cancelled automatically but does not advance
     * with the test scheduler, and the VM's state never leaves `Loading`.
     */
    @AfterTest
    fun cleanup() = fakeRepo.clear()

    private fun TestScope.createVm(): TagGroupsViewModel {
        val authRepo = FakeAuthRepository(Session.Anonymous(UserId("user-a")))
        val currentUser = FakeProfileAwareCurrentUser(authRepo, scope = backgroundScope)
        advanceUntilIdle()
        return TagGroupsViewModel(
            tagGroupRepo = fakeRepo,
            createTagGroup = CreateTagGroupUseCase(
                repo = fakeRepo,
                clock = FakeClock(NOW),
                currentUser = currentUser,
            ),
            deleteTagGroup = DeleteTagGroupUseCase(fakeRepo),
            // backgroundScope owns the VM's lifecycle: runTest cancels it when
            // the body completes, which terminates the observeAll() collector —
            // it never completes on its own, and a TestScope-owned scope makes
            // runTest wait out its full one-minute timeout instead.
            // The matching rule on the other side: drive it with runCurrent(),
            // not advanceUntilIdle(), or the state never leaves Loading.
            scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
        )
    }

    @Test
    fun `an empty repository settles on Empty`() = runTest {
        val vm = createVm()
        runCurrent()

        assertIs<TagGroupsUiState.Empty>(vm.state.value)
    }

    @Test
    fun `a populated repository settles on Content`() = runTest {
        fakeRepo.seed(group(id = "g1", name = "Work"))
        fakeRepo.seed(group(id = "g2", name = "Home"))

        val vm = createVm()
        runCurrent()

        val state = assertIs<TagGroupsUiState.Content>(vm.state.value)
        assertEquals(listOf("g1", "g2"), state.groups.map { it.id.value })
    }

    /**
     * The `catch` branch. Without it a failing stream ends the collection and
     * the screen stays on whatever state it last had — `Loading`, forever.
     */
    @Test
    fun `a failing stream surfaces Error instead of staying on Loading`() = runTest {
        fakeRepo.failWith(RuntimeException("db gone"))

        val vm = createVm()
        runCurrent()

        val state = assertIs<TagGroupsUiState.Error>(vm.state.value)
        assertTrue(
            state.message.isNotBlank(),
            "Error must carry a user-presentable message, got \"${state.message}\"",
        )
    }

    @Test
    fun `Create reaches the repository with the intent's arguments`() = runTest {
        val vm = createVm()
        runCurrent()

        vm.onIntent(TagGroupsIntent.Create(name = "Work", color = 0xFF112233.toInt()))
        runCurrent()

        val created = fakeRepo.created
        assertEquals(1, created.size, "expected exactly one create, got ${created.size}")
        assertEquals("Work", created.single().name)
        assertEquals(0xFF112233.toInt(), created.single().color)
    }

    @Test
    fun `Delete reaches the repository with the intent's id`() = runTest {
        fakeRepo.seed(group(id = "g1", name = "Work"))
        val vm = createVm()
        runCurrent()

        vm.onIntent(TagGroupsIntent.Delete(TagGroupId("g1")))
        runCurrent()

        assertEquals(listOf("g1"), fakeRepo.deleted)
    }

    /**
     * The ViewModel's contract is that a bad intent is a rejected write, not a
     * crash on the collector thread — `CreateTagGroupUseCase` validates, and the
     * VM's job is only to pass the argument through.
     */
    @Test
    fun `an invalid Create is rejected without writing`() = runTest {
        val vm = createVm()
        runCurrent()

        vm.onIntent(TagGroupsIntent.Create(name = "  ", color = 1))
        runCurrent()

        assertTrue(fakeRepo.created.isEmpty(), "a blank name must not reach the repository")
    }

    private fun group(id: String, name: String) = TagGroup(
        id = TagGroupId(id),
        name = name,
        color = 0xFF445566.toInt(),
        createdAt = NOW,
        updatedAt = NOW,
        userId = UserId("user-a"),
    )

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        /**
         * In-memory [TagGroupRepository]. Only what the VM touches is
         * implemented; the rest throws rather than returning a plausible-looking
         * default, so a test that starts depending on an unimplemented method
         * fails loudly instead of passing on a stub.
         */
        class FakeTagGroupRepository : TagGroupRepository {
            private val groups = MutableStateFlow<List<TagGroup>>(emptyList())
            val created = mutableListOf<CreateTagGroupInput>()
            val deleted = mutableListOf<String>()

            @Volatile
            var failure: Throwable? = null

            fun seed(group: TagGroup) {
                groups.value = groups.value + group
            }

            fun failWith(throwable: Throwable) {
                failure = throwable
            }

            fun clear() {
                groups.value = emptyList()
                created.clear()
                deleted.clear()
                failure = null
            }

            override fun observeAll(): Flow<List<TagGroup>> = groups.map { list ->
                failure?.let { throw it }
                list
            }

            override suspend fun create(input: CreateTagGroupInput): Result<TagGroup> {
                created += input
                val group = TagGroup(
                    id = TagGroupId("created-${created.size}"),
                    name = input.name,
                    color = input.color,
                    createdAt = NOW,
                    updatedAt = NOW,
                    userId = UserId("user-a"),
                )
                groups.value = groups.value + group
                return Result.success(group)
            }

            override suspend fun delete(id: TagGroupId): Result<Unit> {
                deleted += id.value
                groups.value = groups.value.filterNot { it.id == id }
                return Result.success(Unit)
            }

            override fun observe(id: TagGroupId): Flow<TagGroup?> = groups.map { list ->
                list.find { it.id == id }
            }

            override suspend fun get(id: TagGroupId): TagGroup? = groups.value.find { it.id == id }

            override suspend fun update(input: UpdateTagGroupInput): Result<TagGroup> =
                error("not used by TagGroupsViewModel")

            override suspend fun upsert(tagGroup: TagGroup): TagGroup = error("not used by TagGroupsViewModel")

            override fun observeInheritedByProject(projectId: ProjectId): Flow<Set<TagGroupId>> =
                error("not used by TagGroupsViewModel")

            override suspend fun setInheritedForProject(
                projectId: ProjectId,
                groupIds: Set<TagGroupId>,
            ): Result<Unit> = error("not used by TagGroupsViewModel")
        }
    }
}
