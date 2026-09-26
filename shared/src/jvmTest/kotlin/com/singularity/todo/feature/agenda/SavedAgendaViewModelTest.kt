package com.singularity.todo.feature.agenda

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.presentation.viewmodel.Draft
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaDraftState
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaSeedStore
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewState
import com.singularity.todo.test.fakes.FakeSavedAgendaViewsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [SavedAgendaViewModel].
 *
 * Pattern (simple by design):
 * - VM scope wraps the TestScope context directly (AutoCloseableCoroutineScope(scope.coroutineContext)) —
 *   no child Job wrapper: the VM's init coroutine completes on its own, so nothing hangs.
 * - VM uses a plain [MutableStateFlow] for state — read `.state.value` directly.
 * - [SavedAgendaDraftState] is tested as a pure class.
 *
 * No `combine`, no `stateIn`, no Turbine, no `expectMostRecentItem`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaViewModelTest {

    private val fakeRepo = FakeSavedAgendaViewsRepository()
    private val seedStore = SavedAgendaSeedStore()

    private fun createVm(mode: SavedAgendaScreenMode, scope: CoroutineScope) = SavedAgendaViewModel(
        deps = SavedAgendaDeps(repo = fakeRepo, clock = Clock, log = Logger),
        mode = mode,
        seedStore = seedStore,
        scope = AutoCloseableCoroutineScope(scope.coroutineContext),
    )

    @AfterTest
    fun cleanup() {
        fakeRepo.clear()
        seedStore.consumeSeed()
    }

    // ─── DraftState unit tests ──────────────────────────────────────────────

    @Test
    fun draftStateSeedingIsIdempotent() {
        val draft = SavedAgendaDraftState()
        assertFalse(draft.state.initialized)

        val first = Draft("A", emptyList(), "A", emptyList(), initialized = true)
        draft.seed(first)
        assertTrue(draft.state.initialized)
        assertEquals("A", draft.state.name)

        // Second seed is ignored
        val second = Draft("B", emptyList(), "B", emptyList(), initialized = true)
        draft.seed(second)
        assertEquals("A", draft.state.name)
    }

    @Test
    fun draftStateSetNameUpdatesNameAndSetsDirty() {
        val draft = SavedAgendaDraftState()
        draft.seed(Draft("Original", emptyList(), "Original", emptyList(), initialized = true))
        assertFalse(draft.state.isDirty)

        draft.setName("Modified")
        assertEquals("Modified", draft.state.name)
        assertTrue(draft.state.isDirty)
        assertEquals("Original", draft.state.originalName)
    }

    @Test
    fun draftStateReorderSectionsSetsDirty() {
        val original = listOf(Section("A", order = 0, selector = Selector.DateBucket(RelativeBucket.Today)))
        val draft = SavedAgendaDraftState()
        draft.seed(Draft("Test", original, "Test", original, initialized = true))
        assertFalse(draft.state.isDirty)

        val reordered = listOf(Section("A", order = 1, selector = Selector.DateBucket(RelativeBucket.Today)))
        draft.reorderSections(reordered)
        assertTrue(draft.state.isDirty)
        assertEquals(reordered, draft.state.sections)
    }

    // ─── Edit mode ────────────────────────────────────────────────────────────────

    @Test
    fun editModeLoadsViewAndSeedsDraft() = runTest {
        val viewId = SavedAgendaViewId.generate()
        fakeRepo.upsertSync(
            SavedAgendaView(
                id = viewId,
                userId = UserId("test-user"),
                name = "My View",
                sectionsJson = """{"title":"My View","sections":[]}""",
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )

        val vm = createVm(SavedAgendaScreenMode.Edit(viewId), this)
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<SavedAgendaViewState.Editing>(state)
        assertEquals("My View", state.draft.name)
        assertEquals("My View", state.draft.originalName)
        assertTrue(state.draft.initialized)
        assertFalse(state.draft.isDirty)
        assertNotNull(state.view)
        assertFalse(state.decodeError)
    }

    @Test
    fun editModeEmitsNotFoundWhenViewMissing() = runTest {
        val viewId = SavedAgendaViewId.generate()
        val vm = createVm(SavedAgendaScreenMode.Edit(viewId), this)
        advanceUntilIdle()

        assertIs<SavedAgendaViewState.NotFound>(vm.state.value)
    }

    @Test
    fun nameChangedSetsIsDirty() = runTest {
        val viewId = SavedAgendaViewId.generate()
        fakeRepo.upsertSync(
            SavedAgendaView(
                id = viewId,
                userId = UserId("test-user"),
                name = "Original",
                sectionsJson = """{"title":"Original","sections":[]}""",
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )

        val vm = createVm(SavedAgendaScreenMode.Edit(viewId), this)
        advanceUntilIdle()

        vm.onIntent(SavedAgendaIntent.NameChanged("Modified"))

        val state = vm.state.value
        assertIs<SavedAgendaViewState.Editing>(state)
        assertEquals("Modified", state.draft.name)
        assertEquals("Original", state.draft.originalName)
        assertTrue(state.draft.isDirty)
    }

    @Test
    fun sectionsReorderedSetsIsDirty() = runTest {
        val viewId = SavedAgendaViewId.generate()
        fakeRepo.upsertSync(
            SavedAgendaView(
                id = viewId,
                userId = UserId("test-user"),
                name = "Test",
                sectionsJson = """{"title":"Test","sections":[{"name":"Today","order":0,"selector":{"type":"DateBucket","bucket":"Today"}}]}""",
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )

        val vm = createVm(SavedAgendaScreenMode.Edit(viewId), this)
        advanceUntilIdle()

        val reordered = listOf(Section("Today", order = 1, selector = Selector.DateBucket(RelativeBucket.Today)))
        vm.onIntent(SavedAgendaIntent.SectionsReordered(reordered))

        val state = vm.state.value
        assertIs<SavedAgendaViewState.Editing>(state)
        assertTrue(state.draft.isDirty)
    }

    @Test
    fun saveUpsertsViewWithUpdatedName() = runTest {
        val viewId = SavedAgendaViewId.generate()
        fakeRepo.upsertSync(
            SavedAgendaView(
                id = viewId,
                userId = UserId("test-user"),
                name = "Original",
                sectionsJson = """{"title":"Original","sections":[]}""",
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )

        val vm = createVm(SavedAgendaScreenMode.Edit(viewId), this)
        advanceUntilIdle()
        vm.onIntent(SavedAgendaIntent.NameChanged("Modified"))
        advanceUntilIdle()
        vm.onIntent(SavedAgendaIntent.Save)
        advanceUntilIdle()

        val saved = fakeRepo.getById(viewId.raw)
        assertNotNull(saved)
        assertEquals("Modified", saved.name)
    }

    @Test
    fun deleteRemovesViewFromRepo() = runTest {
        val viewId = SavedAgendaViewId.generate()
        fakeRepo.upsertSync(
            SavedAgendaView(
                id = viewId,
                userId = UserId("test-user"),
                name = "To Delete",
                sectionsJson = """{"title":"To Delete","sections":[]}""",
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )

        val vm = createVm(SavedAgendaScreenMode.Edit(viewId), this)
        advanceUntilIdle()
        vm.onIntent(SavedAgendaIntent.Delete)
        advanceUntilIdle()

        val saved = fakeRepo.getById(viewId.raw)
        assertNull(saved)
    }

    // ─── Create mode ─────────────────────────────────────────────────────────────

    @Test
    fun createModeSeedsDraftFromSeed() = runTest {
        val seed = AgendaDefinition(
            title = "Fresh View",
            sections = listOf(Section("Today", order = 0, selector = Selector.DateBucket(RelativeBucket.Today))),
        )

        val vm = createVm(SavedAgendaScreenMode.Create(seed), this)
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<SavedAgendaViewState.Editing>(state)
        assertNull(state.view)
        assertEquals("Fresh View", state.draft.name)
        assertEquals("Fresh View", state.draft.originalName)
        assertTrue(state.draft.initialized)
        assertFalse(state.draft.isDirty)
        assertEquals(1, state.draft.sections.size)
    }

    @Test
    fun createModeNameChangedSetsIsDirty() = runTest {
        val seed = AgendaDefinition(title = "Seed", sections = emptyList())
        val vm = createVm(SavedAgendaScreenMode.Create(seed), this)
        advanceUntilIdle()

        vm.onIntent(SavedAgendaIntent.NameChanged("Custom Name"))

        val state = vm.state.value
        assertIs<SavedAgendaViewState.Editing>(state)
        assertEquals("Custom Name", state.draft.name)
        assertTrue(state.draft.isDirty)
    }

    @Test
    fun createModeSaveCreatesNewView() = runTest {
        val seed = AgendaDefinition(title = "Brand New", sections = emptyList())
        val vm = createVm(SavedAgendaScreenMode.Create(seed), this)
        advanceUntilIdle()

        vm.onIntent(SavedAgendaIntent.NameChanged("Brand New Custom"))
        advanceUntilIdle()
        vm.onIntent(SavedAgendaIntent.Save)
        advanceUntilIdle()

        val allViews = fakeRepo.getAll()
        assertTrue(allViews.isNotEmpty(), "Expected at least one view, got ${allViews.size}")
        assertEquals("Brand New Custom", allViews.first().name)
    }

    @Test
    fun createModeDeleteIsNoOp() = runTest {
        val seed = AgendaDefinition(title = "To Delete", sections = emptyList())
        val vm = createVm(SavedAgendaScreenMode.Create(seed), this)
        advanceUntilIdle()

        vm.onIntent(SavedAgendaIntent.Delete)

        val allViews = fakeRepo.getAll()
        assertTrue(allViews.isEmpty())
    }

    // ─── canSave derivation ──────────────────────────────────────────────────────

    @Test
    fun canSaveIsFalseWhenNotDirty() = runTest {
        val seed = AgendaDefinition(title = "Test", sections = emptyList())
        val vm = createVm(SavedAgendaScreenMode.Create(seed), this)
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<SavedAgendaViewState.Editing>(state)
        // After seeding: initialized=true, name=Test, isDirty=false → canSave=false
        assertFalse(state.canSave, "canSave should be false when not dirty")
    }

    // ─── markSaved ───────────────────────────────────────────────────────────

    @Test
    fun markSavedResetsIsDirtyToFalse() {
        val sections = listOf(Section("Today", 0, Selector.DateBucket(RelativeBucket.Today)))
        val draft = SavedAgendaDraftState()
        draft.seed(Draft("Name", sections, "Name", sections, initialized = true))
        assertFalse(draft.state.isDirty)

        // Modify name → isDirty
        draft.setName("Modified")
        assertTrue(draft.state.isDirty)

        // markSaved → isDirty cleared
        draft.markSaved()
        assertFalse(draft.state.isDirty)
        assertEquals("Modified", draft.state.name)
        assertEquals("Modified", draft.state.originalName)
    }

    @Test
    fun markSavedClearsDirtyAfterSectionChange() {
        val sections = listOf(Section("Today", 0, Selector.DateBucket(RelativeBucket.Today)))
        val draft = SavedAgendaDraftState()
        draft.seed(Draft("Name", sections, "Name", sections, initialized = true))
        assertFalse(draft.state.isDirty)

        draft.reorderSections(listOf(Section("Today", 1, Selector.DateBucket(RelativeBucket.Today))))
        assertTrue(draft.state.isDirty)

        draft.markSaved()
        assertFalse(draft.state.isDirty)
    }

    // ─── SavedAgendaViewFactory ──────────────────────────────────────────────

    @Test
    fun factoryCreateGeneratesNewId() {
        val now = Instant.fromEpochMilliseconds(1_000_000)
        val view = SavedAgendaViewFactory.create(UserId("user-1"), "My View", """{"title":"My View","sections":[]}""", now)

        assertEquals(UserId("user-1"), view.userId)
        assertEquals("My View", view.name)
        assertEquals("""{"title":"My View","sections":[]}""", view.sectionsJson)
        assertEquals(now, view.createdAt)
        assertEquals(now, view.updatedAt)
        // Id is generated (not empty)
        assertTrue(view.id.raw.isNotBlank())
    }

    @Test
    fun factoryUpdatePreservesIdAndUserIdAndCreatedAt() {
        val now = Instant.fromEpochMilliseconds(1_000_000)
        val original = SavedAgendaView(
            id = com.singularity.todo.feature.agenda.SavedAgendaViewId("original-id"),
            userId = UserId("user-1"),
            name = "Original",
            sectionsJson = """{"title":"Original","sections":[]}""",
            createdAt = Instant.fromEpochMilliseconds(500_000),
            updatedAt = Instant.fromEpochMilliseconds(800_000),
        )

        val updated = SavedAgendaViewFactory.update(original, "Updated", """{"title":"Updated","sections":[]}""", now)

        assertEquals(com.singularity.todo.feature.agenda.SavedAgendaViewId("original-id"), updated.id)
        assertEquals(UserId("user-1"), updated.userId)
        assertEquals(Instant.fromEpochMilliseconds(500_000), updated.createdAt)
        assertEquals(now, updated.updatedAt)
        assertEquals("Updated", updated.name)
    }

    @Test
    fun factoryDuplicateForProfileRegeneratesIdAndChangesUserId() {
        val now = Instant.fromEpochMilliseconds(1_000_000)
        val original = SavedAgendaView(
            id = com.singularity.todo.feature.agenda.SavedAgendaViewId("original-id"),
            userId = UserId("user-1"),
            name = "Shared View",
            sectionsJson = """{"title":"Shared View","sections":[]}""",
            createdAt = Instant.fromEpochMilliseconds(500_000),
            updatedAt = Instant.fromEpochMilliseconds(800_000),
        )

        val copy = SavedAgendaViewFactory.duplicateForProfile(original, UserId("user-2"), now)

        assertEquals(UserId("user-2"), copy.userId)
        assertEquals("Shared View", copy.name)
        assertEquals("""{"title":"Shared View","sections":[]}""", copy.sectionsJson)
        // New id generated
        assertTrue(copy.id.raw != original.id.raw)
        // Timestamps reset
        assertEquals(now, copy.createdAt)
        assertEquals(now, copy.updatedAt)
    }
}
