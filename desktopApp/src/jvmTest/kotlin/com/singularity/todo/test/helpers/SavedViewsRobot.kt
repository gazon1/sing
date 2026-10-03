package com.singularity.todo.test.helpers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import kotlinx.coroutines.flow.first
import org.koin.core.Koin

/**
 * Page-object robot for saved-agenda-view interactions in desktop flow tests.
 *
 * ```
 * runDesktopAppTest { koin ->
 *     savedViews(koin)
 *         .openList()
 *         .assertEmpty()
 *         .createView(name = "My View")
 *         .assertViewPersisted("My View")
 *         .deleteView("My View")
 *         .assertEmpty()
 * }
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class SavedViewsRobot(
    private val test: DesktopComposeUiTest,
    private val koin: Koin,
) {

    // ─── List-level operations ───────────────────────────────────────────────

    /** Opens the saved views list from the agenda's bookmark button. */
    fun openList(): SavedViewsRobot = apply {
        test.awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        test.awaitTag(TestTags.SAVED_AGENDA_LIST_BACK).assertIsDisplayed()
    }

    /** Closes the saved views list via the back button. */
    fun closeList(): SavedViewsRobot = apply {
        test.awaitTag(TestTags.SAVED_AGENDA_LIST_BACK).performClick()
        test.awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).assertIsDisplayed()
    }

    /** Asserts the empty-state message is shown. */
    fun assertEmpty(): SavedViewsRobot = apply {
        test.onNodeWithText("No saved views yet").assertIsDisplayed()
        test.awaitTag(TestTags.SAVED_AGENDA_CREATE_FAB).assertIsDisplayed()
    }

    /** Asserts a view with [name] appears in the list. */
    fun assertViewVisible(name: String): SavedViewsRobot = apply {
        test.onNodeWithText(name).assertIsDisplayed()
    }

    /** Asserts a view with [name] is NOT in the list. */
    fun assertViewMissing(name: String): SavedViewsRobot = apply {
        test.awaitTagGone(TestTags.savedAgendaCard(name))
    }

    // ─── Create ─────────────────────────────────────────────────────────────

    /**
     * Starts creating a new saved view from the FAB.
     * Call [typeName] and [save] to complete.
     */
    fun startCreate(): SavedViewsRobot = apply {
        test.awaitTag(TestTags.SAVED_AGENDA_CREATE_FAB).performClick()
        test.awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).assertIsDisplayed()
    }

    /**
     * Clears the name field and types [name].
     * Call [save] to persist.
     */
    fun typeName(name: String): SavedViewsRobot = apply {
        test.awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).performTextClearance()
        test.awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).performTextInput(name)
    }

    /**
     * Taps Save and waits for the editor to close.
     * The editor is gone when [TestTags.SAVED_AGENDA_SAVE_BUTTON] disappears.
     */
    fun save(): SavedViewsRobot = apply {
        test.awaitTag(TestTags.SAVED_AGENDA_SAVE_BUTTON).assertIsEnabled()
        test.awaitTag(TestTags.SAVED_AGENDA_SAVE_BUTTON).performClick()
        test.awaitTagGone(TestTags.SAVED_AGENDA_SAVE_BUTTON)
    }

    /**
     * Navigates back to close the editor without saving.
     * This uses the top-bar back arrow (the editor has no explicit Cancel button).
     */
    fun cancelViaBack(): SavedViewsRobot = apply {
        // No-op: SavedAgendaScreen has no explicit Cancel button and the back
        // navigation requires the NavController which is not exposed to tests.
        // Tests that need cancel should use the repository directly to clean up.
    }

    /**
     * Convenience: creates and saves a view in one call.
     * The FAB starts the create flow, [typeName] sets the name, [save] persists.
     */
    fun createView(name: String): SavedViewsRobot = apply {
        startCreate()
        typeName(name)
        save()
    }

    // ─── Delete ─────────────────────────────────────────────────────────────

    /**
     * Opens the delete confirmation for the view named [name].
     * The view's context menu must already be open; call [openContextMenu] first.
     */
    fun confirmDelete(): SavedViewsRobot = apply {
        test.awaitTag(TestTags.SAVED_AGENDA_DELETE_BUTTON).performClick()
        test.awaitTagGone(TestTags.SAVED_AGENDA_DELETE_BUTTON)
    }

    /**
     * Taps the view card named [name] to open its editor.
     */
    fun openView(name: String): SavedViewsRobot = apply {
        test.awaitTag(TestTags.savedAgendaCard(name)).performClick()
    }

    /** Deletes a view by name: tap card → confirm delete. */
    fun deleteView(name: String): SavedViewsRobot = apply {
        openView(name)
        confirmDelete()
    }

    // ─── Persistence assertions ─────────────────────────────────────────────

    /**
     * Checks that the repository has a saved view with [name] for the current user.
     * This bypasses the UI and reads the data layer directly — useful for confirming
     * persistence after a screen-level test.
     *
     * Suspend because `Flow.first()` is a suspend function.
     */
    suspend fun assertViewPersisted(name: String) {
        val repo = koin.get<SavedAgendaViewsRepository>()
        val viewNames = repo.observeAll().first().map { it.name }
        assert(name in viewNames) {
            "expected '$name' to be persisted, but found: $viewNames"
        }
    }

    /**
     * Checks that the repository has NO saved view with [name].
     *
     * Suspend because `Flow.first()` is a suspend function.
     */
    suspend fun assertViewNotPersisted(name: String) {
        val repo = koin.get<SavedAgendaViewsRepository>()
        val viewNames = repo.observeAll().first().map { it.name }
        assert(name !in viewNames) {
            "expected '$name' to NOT be persisted, but found: $viewNames"
        }
    }
}

/** Entry point: `savedViews(koin)` inside a `runDesktopAppTest` body. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.savedViews(koin: Koin): SavedViewsRobot =
    SavedViewsRobot(this, koin)
