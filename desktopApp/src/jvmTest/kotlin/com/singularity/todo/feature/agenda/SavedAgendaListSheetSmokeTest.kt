@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.savedViews
import com.singularity.todo.test.helpers.tapTab
import kotlin.test.Test
import org.junit.jupiter.api.Tag as JunitTag
import org.koin.core.Koin

/**
 * Desktop JVM Compose smoke tests for the saved-views list's two overlays.
 *
 * The copy-to-profile sheet and the delete confirmation are opened from a card's
 * overflow menu, so these tests go through the whole list screen — the overlays
 * live in `SavedAgendaListScreen`, not in the stateless `SavedAgendaListContent`
 * that a component test can mount on its own.
 *
 * ## Driving the overflow menu on desktop
 *
 * The menu is a `DropdownMenu` in its own window, and its test tags are there for
 * the Android tier: on desktop the items are addressed by what the user reads,
 * together with a click action — the shell's AWT menu bar renders a plain
 * "Delete"/"Edit" label with no click action, so text alone is ambiguous. This
 * is the same technique `SavedAgendaEditFlowTest` uses for "Edit".
 *
 * ## Dismissal
 *
 * The delete confirmation has a Cancel button (`TestTags.Dialog.DISMISS`).
 * The copy sheet is a `ListPickerSheet`, which has no control that means only
 * "close": a sheet of choices goes away when a choice is made, so the test picks
 * the default profile. Both tests assert the overlay is on screen *before*
 * dismissing it — a test that only asserts it is gone afterwards would pass on a
 * screen that never opened it.
 *
 * Run with: ./gradlew :desktopApp:test --tests "*SavedAgendaListSheetSmokeTest"
 */
@JunitTag("slow")
@JunitTag("slow:compose")
class SavedAgendaListSheetSmokeTest {
    private suspend fun seedView(
        koin: Koin,
        name: String,
    ) {
        koin.get<SavedAgendaViewsRepository>().upsert(
            SavedAgendaViewFactory.create(
                userId = UserId.anonymous,
                name = name,
                sectionsJson = AgendaDefinition(name, AgendaPresets.All.sections).toSectionsJson(),
                now = kotlin.time.Clock.System.now(),
            ),
        )
    }

    @Test
    fun `the copy to profile sheet opens from a card and closes on a pick`() =
        runDesktopAppTest { koin ->
            seedView(koin, "Copyable View")

            tapTab("Inbox")
            savedViews(koin).openList()
            assertTagDisplayed(TestTags.savedAgendaCard("copyable_view"))

            clickText("⋮")
            onAllNodes(hasText("Copy to profile") and hasClickAction()).onFirst().performClick()

            assertTagDisplayed(TestTags.SAVED_AGENDA_COPY_TO_PROFILE_SHEET)

            // "Default" is the subtitle of the default profile's row, so the pick
            // does not depend on what the profile is named. Matched with a click
            // action and taken as the first, because the shell can carry an
            // unrelated "Default" label behind the sheet.
            onAllNodes(hasText("Default") and hasClickAction()).onFirst().performClick()

            awaitTagGone(TestTags.SAVED_AGENDA_COPY_TO_PROFILE_SHEET)
        }

    @Test
    fun `the delete confirmation opens from a card and its Cancel closes it`() =
        runDesktopAppTest { koin ->
            seedView(koin, "Deletable View")

            tapTab("Inbox")
            savedViews(koin).openList()
            assertTagDisplayed(TestTags.savedAgendaCard("deletable_view"))

            clickText("⋮")
            onAllNodes(hasText("Delete") and hasClickAction()).onFirst().performClick()

            assertTextDisplayed("Delete view?")

            clickTag(TestTags.Dialog.DISMISS)

            onAllNodesWithText("Delete view?").assertCountEquals(0)
        }
}
