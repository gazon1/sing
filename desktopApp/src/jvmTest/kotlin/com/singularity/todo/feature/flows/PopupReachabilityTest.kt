package com.singularity.todo.feature.flows

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.countNodesWithTagInMainRoot
import com.singularity.todo.test.helpers.mainTreeSize
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other two popups, measured the way the sheet was measured.
 *
 * ## What this pins
 *
 * `Maestro/CONVENTIONS.md` tells a writer which tier can reach a surface. The table had
 * two rows that were never measured — the `AlertDialog` row and the `DropdownMenu` row —
 * and the `DropdownMenu` row was **wrong**: it said a dropdown menu could not be reached
 * from a JVM test, and it can.
 *
 * The measurement, on this repository, at this commit:
 *
 * | Surface | Roots before → after | The main tree | The popup's own content |
 * |---|---|---|---|
 * | `DropdownMenu` | 2 → 3 | unchanged | in the third root, reachable |
 * | `AlertDialog` | 2 → 3 | unchanged | in the third root, reachable |
 *
 * So both popups behave like the sheet: they open **their own window**, and their
 * content is reachable from a JVM test through the cross-root search — but it is *not*
 * in the app's own window, and a helper that counts a tag in the main root finds
 * nothing.
 *
 * ## Why this matters beyond the table
 *
 * A tier table that says "no" where the answer is "yes" does not merely cost a test. It
 * tells the next person to write a Maestro flow for something a JVM test can do — and
 * Maestro flows run on a device, in a different tier, with a different set of selectors.
 * The cost of a wrong row is paid in the wrong place.
 *
 * The same failure happened once already: the sheet row said "no", and it was measured
 * on an editor row that did nothing at all. See `SheetReachabilityTest`, which pins the
 * sheet's three claims.
 *
 * @see Maestro/CONVENTIONS.md "Choosing a Tier: A Sheet Opens Its Own Window"
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PopupReachabilityTest {

    /**
     * A `DropdownMenu` opens its own window, and its items are in the scene.
     *
     * The menu is the task editor's overflow: the "More" button opens a `DropdownMenu`
     * with the editor's items in it, and each item carries a tag.
     */
    @Test
    @DisplayName("a DropdownMenu opens its own window, and its items are in the scene")
    fun a_dropdown_menu_opens_its_own_window() = runDesktopAppTest(clock = CLOCK) { koin ->
        tasks(koin).given(due = TODAY, title = "Overflow task")
        awaitTag(TestTags.taskItem("Overflow task")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        val beforeMenu = mainTreeSize()
        clickTag(TestTags.TASK_EDITOR_MORE_MENU)
        awaitTag(TestTags.EditorOverflow.ARCHIVE)

        assertEquals(
            beforeMenu,
            mainTreeSize(),
            "the main semantics tree grew when the menu opened; if a popup is now " +
                "reachable in the main tree, update Maestro/CONVENTIONS.md and the " +
                "coverage matrix",
        )
        assertEquals(
            0,
            countNodesWithTagInMainRoot(TestTags.EditorOverflow.ARCHIVE),
            "the menu's item was found in the main tree; the menu is supposed to open " +
                "its own window",
        )
        assertEquals(
            1,
            countNodesWithTagInAnyRoot(TestTags.EditorOverflow.ARCHIVE),
            "the menu's item was not found in the scene; a popup opens its own window, " +
                "and that window is part of the scene",
        )
    }

    /**
     * An `AlertDialog` opens its own window, and its buttons are in the scene.
     *
     * The dialog is the saved view's delete confirmation: a `ConfirmActionDialog`, which
     * is a real `AlertDialog` and carries the shared `dialog_confirm` tag.
     */
    @Test
    @DisplayName("an AlertDialog opens its own window, and its buttons are in the scene")
    fun an_alert_dialog_opens_its_own_window() = runDesktopAppTest(clock = CLOCK) { koin ->
        createSavedView(koin, "Dialog View")

        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        awaitTag(TestTags.savedAgendaCard("dialog_view"))

        // The overflow lives on the list card; the card itself opens the Results view.
        clickText("⋮")
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().performClick()
        awaitTag(TestTags.SAVED_AGENDA_DELETE_BUTTON)

        val beforeDialog = mainTreeSize()
        awaitTag(TestTags.SAVED_AGENDA_DELETE_BUTTON).performClick()
        awaitTag(TestTags.Dialog.CONFIRM)

        assertEquals(
            beforeDialog,
            mainTreeSize(),
            "the main semantics tree grew when the dialog opened; if a popup is now " +
                "reachable in the main tree, update Maestro/CONVENTIONS.md and the " +
                "coverage matrix",
        )
        assertEquals(
            0,
            countNodesWithTagInMainRoot(TestTags.Dialog.CONFIRM),
            "the dialog's button was found in the main tree; the dialog is supposed to " +
                "open its own window",
        )
        assertEquals(
            1,
            countNodesWithTagInAnyRoot(TestTags.Dialog.CONFIRM),
            "the dialog's button was not found in the scene; a dialog opens its own " +
                "window, and that window is part of the scene",
        )
        assertTrue(
            mainTreeSize() > 0,
            "the main tree should never be empty — an empty one would make the " +
                "assertions above vacuous",
        )
    }

    /** A saved view the agenda's list renders, with its overflow menu. */
    private suspend fun createSavedView(koin: Koin, name: String) {
        val repo = koin.get<SavedAgendaViewsRepository>()
        val definition = AgendaDefinition(name, AgendaPresets.Inbox.sections)
        repo.upsert(
            SavedAgendaViewFactory.create(
                userId = UserId.anonymous,
                name = name,
                sectionsJson = definition.toSectionsJson(),
                now = kotlin.time.Clock.System.now(),
            ),
        )
    }

    private companion object {
        val FIXED_NOW = kotlin.time.Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK = FakeClock(FIXED_NOW)
        val TODAY: LocalDate = LocalDate(2026, 9, 16)
    }
}
