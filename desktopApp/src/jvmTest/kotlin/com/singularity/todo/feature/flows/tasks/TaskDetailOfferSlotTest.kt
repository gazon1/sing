package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfContentDescription
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The slot the task detail keeps for its offers.
 *
 * The offers — the first-run banner and the pending AI proposals — have a place of
 * their own: under the description, above the attribute rows. Three things have to
 * hold there, and none of them is visible in a screenshot:
 *
 *  - a task with nothing to offer shows no offer section at all, and no gap where
 *    the section would have been;
 *  - a pending proposal is drawn above the attribute rows, not below them;
 *  - both of them fit the narrowest phone a person holds.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailOfferSlotTest {
    @Test
    @DisplayName("a task with nothing to offer leaves no gap above the attribute rows")
    fun a_task_with_nothing_to_offer_leaves_no_gap_above_the_attribute_rows() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            // The seeded task is as old as the epoch, so it is past the window in which
            // the first-run offer is drawn, and it carries no proposal.
            tasks(koin).given(
                due = todayAt(TEST_CLOCK, TEST_ZONE),
                title = "Quiet task",
            )

            tapTab("Today")
            awaitTag(TestTags.taskItem("Quiet task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // Nothing to offer, so there is nothing on the screen.
            assertTagNotExists(TestTags.FIRST_RUN_SECTION)
            assertTagNotExists(TestTags.TASK_DETAIL_PROPOSALS_SECTION)

            // And no gap where the offer would have been. An empty offer column is still
            // a child of the editor's column, so its spacing puts one extra gap between
            // the description and the first row below it of every task in the app.
            val description = unclippedBoundsOfContentDescription(DESCRIPTION)
            val firstRow = firstRowBelowTheOfferSlot()
            val gap = firstRow.top - description.bottom
            assertTrue(
                gap < TaskSpacing.md + TaskSpacing.md,
                "the empty offer slot left a gap of $gap between the description " +
                    "($description) and the first row below it ($firstRow)",
            )
        }

    @Test
    @DisplayName("a pending proposal is drawn above the attribute rows")
    fun a_pending_proposal_is_drawn_above_the_attribute_rows() {
        val proposals = FakeProposalRepository(TEST_CLOCK)
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides =
                module {
                    single<TimeZoneProvider> { TestTimeZone }
                    single<ProposalRepository> { proposals }
                },
        ) { koin ->
            val robot = tasks(koin)
            robot.given(
                due = todayAt(TEST_CLOCK, TEST_ZONE),
                title = "Task with a proposal",
            )
            proposals.save(proposalFor(robot.lastTaskId!!, koin.get<ProfileAwareCurrentUser>().scopedUserId.value))

            tapTab("Today")
            awaitTag(TestTags.taskItem("Task with a proposal")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The proposal is on the screen of the task detail.
            assertTagDisplayed(TestTags.TASK_DETAIL_PROPOSALS_SECTION)

            // And above the attribute rows. The priority row is always drawn and it is
            // the first of them, so it is the anchor the order is measured against.
            val stack = unclippedBoundsOfTag(TestTags.TASK_DETAIL_PROPOSALS_SECTION)
            val priority = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTrue(
                stack.bottom <= priority.top,
                "the proposal stack is drawn below the attribute rows: stack $stack, priority $priority",
            )
        }
    }

    @Test
    @DisplayName("the offers fit the narrowest phone screen")
    fun the_offers_fit_the_narrowest_phone_screen() {
        val proposals = FakeProposalRepository(TEST_CLOCK)
        runDesktopAppTest(
            clock = TEST_CLOCK,
            width = NARROW_SCREEN_WIDTH,
            height = NARROW_SCREEN_HEIGHT,
            overrides =
                module {
                    single<TimeZoneProvider> { TestTimeZone }
                    single<ProposalRepository> { proposals }
                },
        ) { koin ->
            val robot = tasks(koin)
            robot.given(
                due = todayAt(TEST_CLOCK, TEST_ZONE),
                title = "Narrow task",
                // Fresh and empty, so the first-run offer is drawn on top of the proposal.
                createdAt = TEST_CLOCK.now(),
            )
            proposals.save(proposalFor(robot.lastTaskId!!, koin.get<ProfileAwareCurrentUser>().scopedUserId.value))

            tapTab("Today")
            awaitTag(TestTags.taskItem("Narrow task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // Both offers are on the screen, and neither of them is cut off at the edge:
            // the whole offer fits between the screen's own margins.
            scrollToTag(TestTags.FIRST_RUN_SECTION)
            assertTagDisplayed(TestTags.FIRST_RUN_SECTION)
            assertFitsTheScreen(unclippedBoundsOfTag(TestTags.FIRST_RUN_SECTION), "the first-run offer")

            scrollToTag(TestTags.TASK_DETAIL_PROPOSALS_SECTION)
            assertTagDisplayed(TestTags.TASK_DETAIL_PROPOSALS_SECTION)
            assertFitsTheScreen(unclippedBoundsOfTag(TestTags.TASK_DETAIL_PROPOSALS_SECTION), "the proposal stack")

            // And the attribute rows below the fold are still reachable: the offers do
            // not push them off the screen.
            scrollToTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
        }
    }

    /**
     * The first row below the offer slot.
     *
     * The slot sits between the description and the attribute rows, and which row
     * comes first depends on the task — the dependency card is drawn only when there
     * is something to depend on. So the row is the topmost of the ones that are
     * drawn, and the priority row is the one that is always there.
     */
    private fun DesktopComposeUiTest.firstRowBelowTheOfferSlot(): DpRect {
        val rows =
            listOf(
                TestTags.TASK_EDITOR_DEPENDENCIES_ROW,
                TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW,
                TestTags.TASK_EDITOR_PRIORITY_ROW,
            ).filter { countNodes(it) > 0 }.map { unclippedBoundsOfTag(it) }
        return rows.minBy { it.top }
    }

    /**
     * The offer is inside the screen's content area.
     *
     * The editor keeps its content between the screen's own margins, so an offer that
     * reaches past them is an offer whose text is cut off.
     */
    private fun assertFitsTheScreen(
        bounds: DpRect,
        what: String,
    ) {
        assertTrue(
            bounds.left >= TaskSpacing.screenPadding &&
                bounds.right <= NARROW_SCREEN_WIDTH.dp - TaskSpacing.screenPadding,
            "$what does not fit a ${NARROW_SCREEN_WIDTH} dp screen: $bounds",
        )
    }

    /**
     * A proposal waiting for a decision, addressed to the task.
     *
     * One change: a new title. It is waiting, so the surface has something to draw.
     */
    private fun proposalFor(
        taskId: TaskId,
        userId: UserId,
    ): AiProposal {
        val proposalId = ProposalId("proposal-offer-slot")
        val kind = ProposalItemKind.SetTaskField(TaskValue.Title("Buy oat milk"))
        val item =
            ProposalItem.pending(
                id = ProposalItemId("proposal-item-offer-slot"),
                proposalId = proposalId,
                kind = kind,
                targetId = taskId.value,
                humanSummary = "Set the title to “Buy oat milk”",
                fingerprint = ProposalFingerprint.of(kind, taskId.value),
            )
        return AiProposal(
            id = proposalId,
            targetKind = ProposalTargetKind.Task,
            targetId = taskId.value,
            userId = userId,
            source = ProposalSource.Detail,
            createdAt = TEST_CLOCK.now(),
            updatedAt = TEST_CLOCK.now(),
            items = listOf(item),
        )
    }

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        val TEST_ZONE: TimeZone = TimeZone.UTC

        /** The description field is named by what it says, not by a test tag. */
        const val DESCRIPTION = "Task description"

        /**
         * The narrowest phone a person holds: 360 dp wide, 640 dp tall.
         *
         * The window is measured in pixels and the desktop scene draws one pixel per dp,
         * so the numbers are the same.
         */
        const val NARROW_SCREEN_WIDTH = 360
        const val NARROW_SCREEN_HEIGHT = 640

        /**
         * Midday UTC on a Saturday, so no timezone that can reach this test can roll the
         * date across a midnight boundary and empty the Today tab.
         */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
    }
}
