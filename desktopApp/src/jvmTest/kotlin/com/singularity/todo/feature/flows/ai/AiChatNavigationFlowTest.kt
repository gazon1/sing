package com.singularity.todo.feature.flows.ai

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertContentDescriptionNotDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The AI chat is a destination of the app, and the menu opens it.
 *
 * The chat had one test, and it was a component test: it mounts the chat screen's
 * content composable with a ViewModel it builds itself. That test says everything about
 * the chat's own behaviour and nothing about the way a person reaches the chat. Three
 * separate facts are involved — the route is bound, the menu offers the entry, and the
 * screen resolves its ViewModel from the app's graph — and a regression in any one of
 * them (an unbound route, a dropped menu entry, a ViewModel that no longer resolves)
 * left the suite green.
 *
 * So this flow goes the way a person does: it opens the menu, selects "AI Chat", and
 * proves the chat arrived by the chat's own input field.
 *
 * The entry that opened it is not the proof. The drawer closes behind the selection, so
 * the entry is off-screen from that moment on, and a flow that asserted on it would
 * pass on a screen that never arrived. The flow therefore asserts both halves: the
 * chat's own node is on screen, and the entry that opened it is not.
 *
 * The chat screen resolves its ViewModel from the app's Koin graph, and that graph binds
 * a fake model: the real one wants a configured provider and would leave a call in
 * flight, so the Compose clock would never go idle and the test would hang rather than
 * fail. The flow never sends a message, so no call is made at all.
 *
 * The flow then leaves again through the shell's own back arrow: the chat draws its own
 * screen bar with no navigation icon, so the shell control is the only way out, and a
 * destination a test can enter but never leave is a destination the suite can only
 * half-check. That arrow is `TestTags.SHELL_BACK_BUTTON` — the one control the shell
 * draws on every destination that has something to go back to.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AiChatNavigationFlowTest {
    @Test
    @DisplayName("the menu opens the AI chat, and the shell's arrow leaves it again")
    fun the_menu_opens_the_ai_chat_and_the_shell_arrow_leaves_it() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            // At the start tab's root there is nothing to go back to, so the shell must
            // not offer the arrow. Without this the test would pass on a shell that
            // drew a back control everywhere, including where pressing it would close
            // the window.
            assertTagNotExists(TestTags.SHELL_BACK_BUTTON)

            tapTab("AI Chat")

            // The chat's own content is the proof of arrival: the input field is
            // rendered by the chat screen and by nothing else.
            assertTagDisplayed(TestTags.CHAT_INPUT)

            // The trigger is gone, which is what makes the assertion above a claim
            // about the destination rather than about the click that started it.
            assertContentDescriptionNotDisplayed("AI Chat")

            // And it can be left again. The chat draws no navigation icon of its own,
            // so the shell's arrow is the way out — and it is the control that means
            // "leave the destination", not one screen of it.
            clickTag(TestTags.SHELL_BACK_BUTTON)

            // The pop is asynchronous: the chat stays composed until the shell has
            // navigated back, so a one-shot "does not exist" right after the click races
            // the transition and fails intermittently under machine load.
            awaitTagGone(TestTags.CHAT_INPUT)

            // Back on the agenda: the shell's FAB is drawn only where the shell itself
            // is on top, so its return is what makes this a claim about the departure
            // rather than about the chat merely becoming invisible.
            assertContentDescriptionDisplayed(DesktopShell.FAB_ADD_TASK)
        }

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    }
}
