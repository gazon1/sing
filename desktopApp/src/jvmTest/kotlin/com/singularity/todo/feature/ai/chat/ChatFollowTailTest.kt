package com.singularity.todo.feature.ai.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The follow-the-tail policy, mounted on its own.
 *
 * Three claims are the whole policy, and all three are about the position of a list after
 * something else moved its content — which is why they are driven on a bare [LazyColumn] rather
 * than through a chat screen with a model behind it:
 *
 * - a conversation restored from the store opens at the end, not at the top;
 * - an answer taller than the window lands with its tail on screen, not its first line;
 * - a reader who scrolled up to re-read an older answer is not pulled back by a new token.
 *
 * [FollowConversationEnd] is public for this reason, the same reason [ChatScreenContent] is: the
 * desktop Compose harness can mount it, and a policy that can only be exercised through the whole
 * screen is a policy nothing proves.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ChatFollowTailTest {
    @Test
    fun a_conversation_that_fits_is_never_scrolled() =
        runIsolatedComposeTest(width = WIDTH, height = HEIGHT) {
            val listState = LazyListState()
            setContent { Conversation(tail = conversation(SHORT_MESSAGES), listState = listState) }

            awaitEnd(listState)

            // There is nowhere to scroll: the content is shorter than the window. A move that
            // aligned the last item's start with the top of the viewport would have pushed the
            // first message off it, so both ends being on screen is the assertion that the list
            // was left alone.
            runOnIdle { assertFalse(listState.canScrollForward, "a conversation that fits was scrolled") }
            assertTagDisplayed(rowTag(0))
            assertTagDisplayed(rowTag(SHORT_MESSAGES - 1))
        }

    @Test
    fun a_conversation_restored_from_the_store_opens_at_the_end() =
        runIsolatedComposeTest(width = WIDTH, height = HEIGHT) {
            val listState = LazyListState()
            setContent {
                Conversation(tail = conversation(RESTORED_MESSAGES), listState = listState)
            }

            awaitEnd(listState)

            assertTagDisplayed(rowTag(RESTORED_MESSAGES - 1))
        }

    @Test
    fun a_reply_taller_than_the_window_lands_its_tail_on_screen() =
        runIsolatedComposeTest(width = WIDTH, height = HEIGHT) {
            val listState = LazyListState()
            setContent {
                Conversation(
                    tail = conversation(SHORT_MESSAGES + 1),
                    listState = listState,
                    lastItemHeight = TALL_REPLY,
                )
            }

            awaitEnd(listState)

            // The reply's own node is on screen, and there is nothing left below the fold: the
            // token a person is waiting for is the last line of that item, not its first.
            assertTagDisplayed(rowTag(SHORT_MESSAGES))
        }

    @Test
    fun a_reader_who_scrolled_up_is_not_moved_by_a_new_message() =
        runIsolatedComposeTest(width = WIDTH, height = HEIGHT) {
            var tail by mutableStateOf(conversation(RESTORED_MESSAGES))
            val listState = LazyListState()
            setContent { Conversation(tail = tail, listState = listState) }

            awaitEnd(listState)

            // The reader takes the wheel and scrolls up, away from the end.
            awaitTag(LIST_TAG).performTouchInput {
                swipeDown(startY = 80f, endY = 520f, durationMillis = 300)
            }
            waitForIdle()
            runOnIdle {
                assertTrue(listState.canScrollForward, "the drag did not scroll the list away from the end")
            }
            val firstVisibleBefore = runOnIdle { listState.firstVisibleItemIndex }

            // The reply streams on. A reader who is re-reading an older answer is left alone.
            runOnIdle { tail = tail.copy(messageCount = tail.messageCount + 1, lastContentLength = 12) }
            waitForIdle()

            runOnIdle {
                assertTrue(listState.canScrollForward, "a new token pulled a reader who had scrolled up to the end")
                assertEquals(
                    firstVisibleBefore,
                    listState.firstVisibleItemIndex,
                    "a new message moved a reader who had scrolled up",
                )
            }
        }

    /** A bare list that draws [ConversationTail.messageCount] rows, the last of them [lastItemHeight]. */
    @Composable
    private fun Conversation(
        tail: ConversationTail,
        listState: LazyListState,
        lastItemHeight: Dp = ROW_HEIGHT,
    ) {
        FollowConversationEnd(listState = listState, tail = tail)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(LIST_TAG),
            state = listState,
        ) {
            items(tail.messageCount) { index ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(if (index == tail.lastItemIndex) lastItemHeight else ROW_HEIGHT)
                            .testTag(rowTag(index)),
                )
            }
        }
    }

    private fun conversation(messages: Int): ConversationTail =
        ConversationTail(
            messageCount = messages,
            lastContentLength = 0,
            questionCount = messages,
            isLoading = false,
        )

    /**
     * Waits until the policy has put the end of the content at the bottom of the viewport.
     *
     * Asserted from the list state rather than from a row being displayed: a row can intersect the
     * viewport while the end of the content is still below it, which is exactly the state a tall
     * reply is in before the move has run.
     */
    private fun DesktopComposeUiTest.awaitEnd(listState: LazyListState) {
        waitUntil(timeoutMillis = TIMEOUT_MS) { !listState.canScrollForward }
        runOnIdle { assertFalse(listState.canScrollForward, "the end of the conversation is below the fold") }
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 600
        const val RESTORED_MESSAGES = 20
        const val SHORT_MESSAGES = 10
        const val TIMEOUT_MS = 5_000L

        val ROW_HEIGHT: Dp = 40.dp
        val TALL_REPLY: Dp = 2_000.dp

        const val LIST_TAG = "follow-tail-list"

        fun rowTag(index: Int): String = "follow-tail-row-$index"
    }
}
