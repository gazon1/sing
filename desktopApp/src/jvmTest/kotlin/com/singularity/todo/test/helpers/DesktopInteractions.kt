package com.singularity.todo.test.helpers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.singularity.todo.core.ui.TestTags

/**
 * Clicks a node with contentDescription [desc]. Waits for it to appear first.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickContentDescription(desc: String) {
    awaitContentDescription(desc).performClick()
}

/**
 * Clicks the back arrow the **screen** draws, not the shell's own.
 *
 * The shell draws a back arrow of its own — `TestTags.SHELL_BACK_BUTTON` — as soon as
 * there is something to pop, and a screen that can be left draws an arrow of its own.
 * Both carry the content description "Back", so the description alone matches two
 * nodes and [clickContentDescription] refuses to choose between them. The tag tells
 * them apart: the shell's carries one, the screens' do not.
 *
 * The screen's arrow is the one a flow means by "leave this screen". The shell's pops
 * the stack of the top-level route, and for a nested destination — the notes editor
 * lives in the notes graph — that pops the whole graph, so the list the flow came from
 * goes with it. The screen's arrow pops only the screen.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickScreenBack() {
    val screenBack =
        onAllNodes(
            hasContentDescription(DesktopShell.BACK) and
                hasTestTag(TestTags.SHELL_BACK_BUTTON).not(),
        )
    if (screenBack.fetchSemanticsNodes().isEmpty()) {
        // No screen drew an arrow of its own — the shell's is the only way back.
        awaitTag(TestTags.SHELL_BACK_BUTTON).performClick()
    } else {
        screenBack.onFirst().performClick()
    }
}

/**
 * Clicks a node with text [text]. Waits for it to appear first.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickText(text: String) {
    awaitText(text).performClick()
}

/**
 * Clicks a checkbox with [testTag]. The node must already be in the tree.
 * Use when the test has already waited for the checkbox to appear.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickCheckbox(testTag: String) {
    onNodeWithTag(testTag).performClick()
}

/**
 * Waits for a node with [tag] and clicks it — the click-and-wait counterpart
 * of [awaitTag] for interactions.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickTag(tag: String) {
    awaitTag(tag).performClick()
}

/**
 * Performs the long-click action of the node with [tag].
 *
 * ## Why the semantic action and not a timed gesture
 *
 * A long press is recognised by a timeout inside the gesture detector, and that
 * timeout runs on the clock of the composition — not on the clock the desktop
 * test drives. A press held for a thousand milliseconds of test time is a press
 * that the detector has not seen the end of yet, so a real gesture never fires
 * here. The semantic action is the same handler the gesture ends up calling,
 * which is what the test is about: *which* long press a node answers, not how
 * long the platform holds a pointer down.
 *
 * The node that is pressed is the one *on the screen*. A pager renders the
 * neighbouring months next to the current one, and a day of the neighbouring
 * month can carry the same test tag as a day of the current month — the
 * off-screen copy is the one a plain lookup finds first, and a gesture that
 * starts outside the window fails instead of pressing anything. [awaitAnyDisplayed]
 * is the repo's answer to that, and this helper uses it rather than measuring
 * bounds itself.
 */
@OptIn(ExperimentalTestApi::class)
suspend fun DesktopComposeUiTest.longClickTag(tag: String) {
    awaitAnyDisplayed(tag)
    onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
}

/**
 * Waits for the input with [tag], then replaces its text with [value].
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.typeIntoTag(
    tag: String,
    value: String,
) {
    awaitTag(tag).performTextReplacement(value)
}

/**
 * Waits for the input with [tag], then clears its text.
 *
 * The counterpart of [typeIntoTag] for a field that has to end up empty.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clearTag(tag: String) {
    awaitTag(tag).performTextClearance()
}

/**
 * Waits for the input with [tag], clears it, then types [value].
 * Use for fields that arrive with seeded content.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clearAndTypeIntoTag(
    tag: String,
    value: String,
) {
    awaitTag(tag).performTextClearance()
    onNodeWithTag(tag).performTextInput(value)
}

/**
 * Waits for the input with [tag], then fires its IME action.
 *
 * A field that submits on the keyboard's "Done" key is driven through this, not through
 * a click on a button that does not exist on that screen.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.submitTag(tag: String) {
    awaitTag(tag).performImeAction()
}

/**
 * Scrolls [tag] into view, then clicks it.
 *
 * `clickTag` asserts the node is already displayed, which is wrong for anything below the
 * fold in a `verticalScroll` screen — and it fails with "not displayed" while the node is
 * present and perfectly clickable. Settings → Calendar is such a screen: the Google panel
 * stacks account, calendar picker, import and sync sections, so the "Sync now" control sits
 * below the viewport on the default 1024x768 test window.
 *
 * [performScrollTo] needs a scrollable ancestor, which these screens have; where it does
 * not, it throws naming that, rather than silently clicking the wrong node.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickTagScrolled(tag: String) {
    awaitTag(tag).performScrollTo().performClick()
}

/**
 * Selects a settings tab by its name — the tab the user reads ("Sync", "Calendar").
 *
 * The wide settings layout draws its tabs as a nav rail: a column of thirteen entries
 * that is taller than the 1024x768 test window, so the tabs of the "Data" group (Sync,
 * Calendar) sit below the fold. Such a tab is *composed*, so a plain click resolves in the
 * semantics tree and lands on a coordinate outside the window — a no-op that leaves the
 * previous tab selected. The failure is silent and surfaces far away, as the next tab's
 * content being missing.
 *
 * So the tab is scrolled into view before it is pressed, the same way [clickTagScrolled]
 * handles a row below the fold of a settings panel.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickSettingsTab(name: String) {
    clickTagScrolled(TestTags.settingsTab(name))
}

/**
 * Waits for a node with [tag] and clicks the first match.
 *
 * A popup on desktop is composed in two windows while it animates — the one it animates
 * into and the one it animates out of — so its buttons are in the tree twice, and a
 * single-node selector refuses to choose. Both copies are the same button, and either
 * of them presses it.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickTagFirst(tag: String) {
    awaitAnyDisplayed(tag)
    onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().performClick()
}

/**
 * Clicks a sheet's close button.
 *
 * A `ModalBottomSheet` on desktop is composed in two windows — the one it animates into
 * and the one it animates out of — so its buttons are in the tree twice and a
 * single-node selector refuses to choose. Both copies are the same button, and either
 * of them dismisses the sheet.
 *
 * The tag is the sheet's own, because the calendar panel's close button has the same
 * content description and is composed behind the editor.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickSheetClose() = clickTagFirst(TestTags.EDITOR_SHEET_CLOSE)

/** [clickTagScrolled] for a node that only has to be *present*, not pressed. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.scrollToTag(tag: String) {
    awaitTag(tag).performScrollTo()
}

/**
 * [clickTagScrolled] for a node identified by content description.
 *
 * The navigation drawer is a scrollable column whose last entry (Settings) sits below
 * the fold of the 1024x768 test window. Such a node is *composed*, so `awaitContentDescription`
 * finds it and a plain `performClick` succeeds without error — but the click lands on a
 * coordinate outside the window and the navigation it names never happens. That failure
 * is silent: the next assertion fails somewhere else entirely. Anything addressed by
 * what the user reads must go through here.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickContentDescriptionScrolled(desc: String) {
    awaitContentDescription(desc).performScrollTo().performClick()
}

/**
 * Clicks a point [offsetDp] from the centre of the node with content description
 * [desc], horizontally.
 *
 * The pointer lands on whatever is under that point, not on the node the lookup
 * found: a tap in the band around a glyph is what a finger does when it misses the
 * glyph, and whether the control answers is the question this asks. A negative
 * [offsetDp] is to the left of the centre.
 *
 * @param desc The content description of the node to click in.
 * @param offsetDp How far from the centre of the node to click, in dp.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.clickContentDescriptionAtOffset(
    desc: String,
    offsetDp: Float,
) {
    val interaction = awaitContentDescription(desc)
    val node = interaction.fetchSemanticsNode()
    // The tree reports the bounds twice — in pixels and in dp — and the ratio of the
    // two is the density the window runs at.
    val bounds = interaction.getUnclippedBoundsInRoot()
    val widthDp = (bounds.right - bounds.left).value
    val pxPerDp = if (widthDp > 0f) node.size.width / widthDp else 1f
    val offsetPx = offsetDp * pxPerDp
    interaction.performTouchInput {
        // The position is in the node's own coordinates, whose origin is its top
        // left corner.
        click(Offset(node.size.width / 2f + offsetPx, node.size.height / 2f))
    }
}

/**
 * Drags the AI chat's message list down, away from the newest message.
 *
 * The chat's follow-the-tail policy reads the reader's scroll from the list's own scroll state —
 * a resting scroll that stopped anywhere but the end is the reader's, because every move the
 * policy makes lands at the end. So a test about "a reader who scrolled up is left alone" has to
 * drag the list, not assign a scroll offset.
 *
 * Downward is away from the end: the conversation grows at the bottom, so revealing an
 * older message means moving the content down.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.dragChatMessagesDown() {
    awaitTag(TestTags.CHAT_MESSAGES).performTouchInput { swipeDown() }
}

/**
 * Drags the chat's message list up, back towards the newest message.
 *
 * The counterpart of [dragChatMessagesDown]. Ending the drag at the end of the list is
 * what tells the policy the reader is following again, so this is how a test returns a
 * reader to the tail.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.dragChatMessagesUp() {
    awaitTag(TestTags.CHAT_MESSAGES).performTouchInput { swipeUp() }
}
