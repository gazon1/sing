package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each agenda tab must evaluate **its own** definition, not the one its ViewModel
 * happened to be constructed with.
 *
 * This is a regression test for a real bug, not a coverage gap. Every NavEntry
 * resolved the *same* `LocalViewModelStoreOwner` (the window), because
 * `Nav3State.toDecoratedEntries` applied only the SaveableStateHolder decorator and
 * never `rememberViewModelStoreNavEntryDecorator`. `koinViewModel` therefore returned
 * the boot-time `AgendaViewModel` to every tab, so switching tabs recomposed
 * `AgendaScreen` with a new definition but kept the old ViewModel — and the old
 * sections. The user saw Today's list on Inbox, and Upcoming's definition was never
 * consulted at all.
 *
 * The discriminators below are deliberate. Every preset defines a "Today" section, so
 * anything dated today passes whether or not the ViewModel is correct — that is how
 * the bug survived a suite that only ever asserted on dated, due-today fixtures. Two
 * fixtures are needed instead:
 *
 * - an **undated** task, which only Inbox's "No Date" section matches;
 * - a **tomorrow** task, which Inbox matches under "Tomorrow" and Today does not
 *   define at all. (Yesterday would be the obvious third choice, but Inbox lists
 *   "Overdue" at order −1 with `discard = true`, so a yesterday-due task is consumed
 *   there before "Yesterday" ever sees it — it renders under "Overdue", correctly.)
 *
 * @see Nav3State.toDecoratedEntries
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class AgendaTabDefinitionFlowTest {

    @Test
    fun inbox_shows_the_no_date_section_the_today_tab_has_no_room_for() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).givenUndated("Call the dentist")

        tapTab("Inbox")
        assertCurrentTab("Inbox")

        awaitTag(TestTags.agendaSection("No Date")).assertIsDisplayed()
        awaitTag(TestTags.taskItem("Call the dentist")).assertIsDisplayed()
    }

    @Test
    fun inbox_evaluates_tomorrow_which_the_today_preset_does_not_define() = runDesktopAppTest(checkA11y = true) { koin ->
        val tomorrow = todayInSystemZone().plus(1, DateTimeUnit.DAY)
        tasks(koin).given(due = tomorrow, title = "Send the invoice")

        tapTab("Inbox")

        awaitTag(TestTags.agendaSection("Tomorrow")).assertIsDisplayed()
        awaitTag(TestTags.taskItem("Send the invoice")).assertIsDisplayed()
    }

    /**
     * The other direction of the same contract: Upcoming has no "No Date" section, so
     * with a stale ViewModel the undated task would still be on screen under Inbox's
     * section name.
     */
    @Test
    fun upcoming_does_not_render_the_inbox_no_date_section() = runDesktopAppTest(checkA11y = true) { koin ->
        val tomorrow = todayInSystemZone().plus(1, DateTimeUnit.DAY)
        tasks(koin)
            .givenUndated("Call the dentist")
            .given(due = tomorrow, title = "Send the invoice")

        tapTab("Upcoming")
        assertCurrentTab("Upcoming")

        awaitTag(TestTags.agendaSection("Tomorrow")).assertIsDisplayed()

        // The undated task is simply not part of this agenda — it must be absent,
        // not relocated under a section name borrowed from the Inbox preset.
        assertEquals(
            0,
            onAllNodesWithTag(TestTags.agendaSection("No Date")).fetchSemanticsNodes().size,
            "Upcoming must not render an Inbox 'No Date' section",
        )
    }
}
