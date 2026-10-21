package com.singularity.todo.feature.agenda

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListState
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListViewModel
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.jupiter.api.Tag

/**
 * Tests for [SavedAgendaListViewModel] (saved agenda views list screen).
 *
 * Pattern: all tests use [testVm] with [SavedAgendaListViewModel].
 * The VM's init collector never completes (Flow observation), so the detached
 * Job pattern is used in the factory. The collector is driven by the shared
 * test scheduler via [runCurrent] / [kotlinx.coroutines.test.advanceUntilIdle].
 *
 * In-memory fakes implement the repository ports directly (no mocks).
 */
@Tag("fast")
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaListViewModelTest {

    private val testUserId = UserId("test-user")
    private val personalProfileId = ProfileId("personal")
    private val workProfileId = ProfileId("work")
    private val epoch0 = Instant.fromEpochMilliseconds(0)

    private fun makeProfile(id: ProfileId, name: String) = Profile(
        id = id,
        name = name,
        emoji = "🏠",
        colorIdx = 0,
        isDefault = false,
        createdAt = epoch0,
        updatedAt = epoch0,
    )

    private fun makeView(id: String, name: String, userId: UserId = testUserId) = SavedAgendaView(
        id = SavedAgendaViewId(id),
        userId = userId,
        name = name,
        sectionsJson = AgendaDefinition(title = name, sections = emptyList()).toSectionsJson(),
        createdAt = epoch0,
        updatedAt = epoch0,
    )

    private fun makeDeps(
        views: List<SavedAgendaView> = emptyList(),
        profiles: List<Profile> = listOf(
            makeProfile(personalProfileId, "Personal"),
            makeProfile(workProfileId, "Work"),
        ),
        deleteError: Exception? = null,
    ): SavedAgendaListDeps {
        val profileRepo = FakeProfilesRepo(profiles)
        return SavedAgendaListDeps(
            repo = FakeRepo(views, deleteError),
            profileRepo = profileRepo,
            currentUser = FakeProfileAwareCurrentUser(initialUserId = testUserId, profileRepository = profileRepo),
        )
    }

    // testVm pattern: detached Job for the never-completing init collector.
    // Named args used because the global testVm has an optional `afterInit` param
    // after `factory`, which blocks trailing-lambda syntax for factory.
    private fun TestScope.testVm(deps: SavedAgendaListDeps) = testVm(
        stateAccessor = { vm: SavedAgendaListViewModel -> vm.stateFlow },
        factory = { ts, _ ->
            SavedAgendaListViewModel(
                deps = deps,
                scope = AutoCloseableCoroutineScope(ts.coroutineContext + Job()),
            )
        },
    )

    // For event-listening tests: set up background collector before first act.
    private fun TestScope.testVmWithEvents(
        deps: SavedAgendaListDeps,
        events: MutableList<SavedAgendaListEvent>,
    ) = testVm(
        stateAccessor = { vm: SavedAgendaListViewModel -> vm.stateFlow },
        afterInit = { vm, bgScope ->
            bgScope.launch { vm.events.collect { events += it } }
        },
        factory = { ts, _ ->
            SavedAgendaListViewModel(
                deps = deps,
                scope = AutoCloseableCoroutineScope(ts.coroutineContext + Job()),
            )
        },
    )

    @Test
    fun `starts in Loading then transitions to Loaded`() = runTest {
        val deps = makeDeps(views = listOf(makeView("v1", "My View")))
        val ctx = testVm(deps)
        // VM starts in Loading; runCurrent drives the init collector to Loaded
        runCurrent()
        val state = ctx.state.value
        assertIs<SavedAgendaListState.Loaded>(state)
        assertEquals(1, state.views.size)
        assertEquals("My View", state.views.first().name)
    }

    @Test
    fun `starts in Loading with empty list`() = runTest {
        val deps = makeDeps(views = emptyList())
        val ctx = testVm(deps)
        runCurrent()
        val state = ctx.state.value
        assertIs<SavedAgendaListState.Loaded>(state)
        assertEquals(0, state.views.size)
    }

    @Test
    fun `Delete removes view from the list`() = runTest {
        val view = makeView("v1", "To Delete")
        val deps = makeDeps(views = listOf(view))
        val ctx = testVm(deps)
        runCurrent()
        assertEquals(1, (ctx.state.value as SavedAgendaListState.Loaded).views.size)

        ctx.act { onIntent(SavedAgendaListIntent.Delete(view.id)) }

        val state = ctx.state.value as SavedAgendaListState.Loaded
        assertEquals(0, state.views.size)
    }

    @Test
    fun `Delete emits ShowError on failure`() = runTest {
        val view = makeView("v1", "To Delete")
        val deps = makeDeps(views = listOf(view), deleteError = Exception("DB error"))
        val events = mutableListOf<SavedAgendaListEvent>()
        val ctx = testVmWithEvents(deps, events)

        ctx.act { onIntent(SavedAgendaListIntent.Delete(view.id)) }

        assertTrue(events.any { it is SavedAgendaListEvent.ShowError })
    }

    @Test
    fun `CopyToProfile duplicates view into the target profile's scoped namespace`() = runTest {
        val view = makeView("v1", "Work View")
        val deps = makeDeps(views = listOf(view))
        val ctx = testVm(deps)
        runCurrent()

        ctx.act { onIntent(SavedAgendaListIntent.CopyToProfile(view.id, workProfileId)) }

        val state = ctx.state.value as SavedAgendaListState.Loaded
        // Should now have original + copy
        assertEquals(2, state.views.size)
        val copies = state.views.filter { it.name == "Work View" }
        assertEquals(2, copies.size)
        // The copy carries the target profile's SCOPED userId ("{profileId}/{raw}"),
        // not the profile row's id — a bare "work" was the cross-user-write bug.
        val userIds = copies.map { it.userId }.toSet()
        assertEquals(setOf(testUserId, UserId("work/test-user")), userIds)
    }
}

/**
 * In-memory [SavedAgendaViewsRepository] for this test — deliberately not user-scoped
 * so a cross-profile copy stays visible to assertions. Optionally fails [delete].
 */
private class FakeRepo(views: List<SavedAgendaView>, private val deleteFail: Exception?) : SavedAgendaViewsRepository {

    private val store = MutableStateFlow(views)

    override fun observeAll(): Flow<List<SavedAgendaView>> = store

    override fun observe(id: SavedAgendaViewId): Flow<SavedAgendaView?> = store.map { views ->
        views.find { it.id == id }
    }

    override suspend fun get(id: SavedAgendaViewId): SavedAgendaView? = store.value.find { it.id == id }

    override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> = runCatchingCancellable {
        store.value = store.value.filter { it.id != view.id } + view
        view
    }

    override suspend fun delete(id: SavedAgendaViewId): Result<Unit> {
        deleteFail?.let { return Result.failure(it) }
        return runCatchingCancellable { store.value = store.value.filter { it.id != id } }
    }

    override suspend fun currentUserId(): String = "test-user"

    override suspend fun deleteAllForCurrentProfile(): Result<Int> = runCatchingCancellable {
        val before = store.value.size
        store.value = emptyList()
        before
    }

    override suspend fun duplicateForProfile(view: SavedAgendaView, targetUserId: String): Result<SavedAgendaView> =
        runCatchingCancellable {
            val now = fakeNow
            val copy = view.copy(
                id = SavedAgendaViewId.generate(),
                userId = UserId(targetUserId),
                createdAt = now,
                updatedAt = now,
            )
            upsert(copy).getOrThrow()
        }
}

/** In-memory [ProfileRepository] for this test. */
private class FakeProfilesRepo(profiles: List<Profile>) : ProfileRepository {

    private val store = MutableStateFlow(profiles.associateBy { it.id })
    private val _activeProfileId = MutableStateFlow(profiles.first().id)

    override fun observeAll(): Flow<List<Profile>> = store.map { it.values.toList() }

    override fun observe(id: ProfileId): Flow<Profile?> = store.map { it[id] }

    override suspend fun get(id: ProfileId): Profile? = store.value[id]

    override suspend fun create(item: Profile): Result<Profile> = runCatchingCancellable {
        store.value = store.value + (item.id to item)
        item
    }

    override suspend fun update(item: Profile): Result<Profile> = runCatchingCancellable {
        store.value = store.value + (item.id to item)
        item
    }

    override suspend fun delete(id: ProfileId): Result<Unit> = runCatchingCancellable {
        store.value = store.value - id
    }

    override fun activeProfile(): Flow<Profile> = _activeProfileId.map { id ->
        requireNotNull(store.value[id]) { "No profile with id $id" }
    }

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    override suspend fun switchTo(id: ProfileId): Result<Unit> = runCatchingCancellable {
        _activeProfileId.value = id
    }

    override suspend fun findByName(name: String): Profile? =
        store.value.values.find { it.name.equals(name, ignoreCase = true) }

    override suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>>) {
        // no-op for tests
    }
}

/** Fixed instant used by the in-memory repo's cross-profile copies (no direct Clock.System). */
private val fakeNow: Instant = Instant.fromEpochMilliseconds(1_760_000_000)
