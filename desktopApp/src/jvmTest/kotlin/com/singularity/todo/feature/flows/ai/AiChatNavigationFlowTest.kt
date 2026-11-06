package com.singularity.todo.feature.flows.ai

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertContentDescriptionNotDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
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
 * The flow ends on the chat screen. Leaving it again is a question about the chat
 * screen's own chrome, which this change does not touch.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AiChatNavigationFlowTest {
    @Test
    @DisplayName("the menu opens the AI chat, and the chat's own input proves it arrived")
    fun the_menu_opens_the_ai_chat() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            tapTab("AI Chat")

            // The chat's own content is the proof of arrival: the input field is
            // rendered by the chat screen and by nothing else.
            assertTagDisplayed(TestTags.CHAT_INPUT)

            // The trigger is gone, which is what makes the assertion above a claim
            // about the destination rather than about the click that started it.
            assertContentDescriptionNotDisplayed("AI Chat")
        }

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    }
}
