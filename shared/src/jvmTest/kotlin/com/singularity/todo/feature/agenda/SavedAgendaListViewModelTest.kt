package com.singularity.todo.feature.agenda

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
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
 * Pattern:
 * - VM scope wraps the TestScope context plus a detached root [Job] — the VM's init
 *   collector never completes, so it must NOT be a child of the test job (runTest
 *   would otherwise fail with UncompletedCoroutinesError). Coroutines on the detached
 *   job are still driven by the shared test scheduler via [runCurrent].
 * - In-memory fakes implement the repository ports directly (no mocks).
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

    private fun TestScope.createVm(deps: SavedAgendaListDeps) = SavedAgendaListViewModel(
        deps = deps,
        scope = AutoCloseableCoroutineScope(coroutineContext + Job()),
    )

    @Test
    fun `starts in Loading then transitions to Loaded`() = runTest {
        val deps = makeDeps(views = listOf(makeView("v1", "My View")))
        val vm = createVm(deps)
        assertEquals(SavedAgendaListState.Loading, vm.state.value)
        runCurrent()
        val state = vm.state.value
        assertIs<SavedAgendaListState.Loaded>(state)
        assertEquals(1, state.views.size)
        assertEquals("My View", state.views.first().name)
    }

    @Test
    fun `starts in Loading with empty list`() = runTest {
        val deps = makeDeps(views = emptyList())
        val vm = createVm(deps)
        assertEquals(SavedAgendaListState.Loading, vm.state.value)
        runCurrent()
        val state = vm.state.value
        assertIs<SavedAgendaListState.Loaded>(state)
        assertEquals(0, state.views.size)
    }

    @Test
    fun `Delete removes view from the list`() = runTest {
        val view = makeView("v1", "To Delete")
        val deps = makeDeps(views = listOf(view))
        val vm = createVm(deps)
        runCurrent()
        assertEquals(1, (vm.state.value as SavedAgendaListState.Loaded).views.size)

        vm.onIntent(SavedAgendaListIntent.Delete(view.id))
        runCurrent()

        val state = vm.state.value as SavedAgendaListState.Loaded
        assertEquals(0, state.views.size)
    }

    @Test
    fun `Delete emits ShowError on failure`() = runTest {
        val view = makeView("v1", "To Delete")
        val deps = makeDeps(views = listOf(view), deleteError = Exception("DB error"))
        val vm = createVm(deps)
        runCurrent()

        val events = mutableListOf<SavedAgendaListEvent>()
        backgroundScope.launch {
            vm.events.collect { events += it }
        }

        vm.onIntent(SavedAgendaListIntent.Delete(view.id))
        runCurrent()

        assertTrue(events.any { it is SavedAgendaListEvent.ShowError })
    }

    @Test
    fun `CopyToProfile duplicates view into the target profile's scoped namespace`() = runTest {
        val view = makeView("v1", "Work View")
        val deps = makeDeps(views = listOf(view))
        val vm = createVm(deps)
        runCurrent()

        vm.onIntent(SavedAgendaListIntent.CopyToProfile(view.id, workProfileId))
        runCurrent()

        val state = vm.state.value as SavedAgendaListState.Loaded
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

    override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> = runCatching {
        store.value = store.value.filter { it.id != view.id } + view
        view
    }

    override suspend fun delete(id: SavedAgendaViewId): Result<Unit> {
        deleteFail?.let { return Result.failure(it) }
        return runCatching { store.value = store.value.filter { it.id != id } }
    }

    override suspend fun currentUserId(): String = "test-user"

    override suspend fun duplicateForProfile(view: SavedAgendaView, targetUserId: String): Result<SavedAgendaView> =
        runCatching {
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

    override suspend fun create(item: Profile): Result<Profile> = runCatching {
        store.value = store.value + (item.id to item)
        item
    }

    override suspend fun update(item: Profile): Result<Profile> = runCatching {
        store.value = store.value + (item.id to item)
        item
    }

    override suspend fun delete(id: ProfileId): Result<Unit> = runCatching {
        store.value = store.value - id
    }

    override fun activeProfile(): Flow<Profile> = _activeProfileId.map { id ->
        requireNotNull(store.value[id]) { "No profile with id $id" }
    }

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    override suspend fun switchTo(id: ProfileId): Result<Unit> = runCatching {
        _activeProfileId.value = id
    }

    override suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>>) {
        // no-op for tests
    }
}

/** Fixed instant used by the in-memory repo's cross-profile copies (no direct Clock.System). */
private val fakeNow: Instant = Instant.fromEpochMilliseconds(1_760_000_000)
