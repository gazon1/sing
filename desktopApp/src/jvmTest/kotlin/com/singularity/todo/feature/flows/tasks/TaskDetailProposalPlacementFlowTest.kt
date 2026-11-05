package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The pending AI proposals of a task are drawn in the upper slot of the detail —
 * under the description, above the attribute rows.
 *
 * ## Why this is worth a flow
 *
 * The proposals card is the one part of the detail a person has to act on: it
 * holds changes the AI made to their task, and each of them waits for a yes or a
 * no. A card that sits below the attribute rows is a card nobody scrolls to, and a
 * change nobody answers is a change that stays pending forever.
 *
 * The check is a comparison of two positions, not of two texts: the card is
 * addressed by its tag, and the attribute rows by theirs, so the assertion is
 * about where the card is rather than about what it says.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailProposalPlacementFlowTest {
    @Test
    fun a_pending_proposal_is_drawn_above_the_attribute_rows() =
        runDesktopAppTest(
            clock = CLOCK,
            checkA11y = true,
        ) { koin ->
            tasks(koin)
                .given(due = today, title = "Buy milk")
                .givenPendingProposal()

            tapTab("Today")
            awaitTag(TestTags.taskItem("Buy milk")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The card is on screen without a scroll: it is in the upper slot,
            // directly under the description.
            assertTagDisplayed(TestTags.TASK_DETAIL_PROPOSALS)

            // And it is above the attribute rows. The attachments row is the first
            // attribute row the detail always draws — the checklist row belongs to
            // the editor, and the dependencies card only appears for a task that
            // has relations to pick from — so a card that moved back under the
            // attribute rows would fail here rather than quietly reading as
            // "somewhere on the screen".
            val proposals = unclippedBoundsOfTag(TestTags.TASK_DETAIL_PROPOSALS)
            val attributeRows = unclippedBoundsOfTag(TestTags.TASK_EDITOR_ATTACHMENTS_ROW)
            assertTrue(
                proposals.bottom <= attributeRows.top,
                "the proposals card ends at ${proposals.bottom} but the attribute rows start " +
                    "at ${attributeRows.top} — the card belongs above them",
            )
        }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)
    }
}
