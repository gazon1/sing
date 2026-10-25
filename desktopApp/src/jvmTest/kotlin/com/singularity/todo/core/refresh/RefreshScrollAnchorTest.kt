@file:OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)

package com.singularity.todo.core.refresh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.fakes.FakeCompletedTaskArchiver
import com.singularity.todo.test.fakes.FakeSyncRepository
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scroll anchor: the gesture only reaches the coordinator if the content
 * can be dragged.
 *
 * The pattern under test is the one the screens in scope use: the screen's own
 * root carries the vertical scroll, and the container wraps it. The pomodoro is
 * the screen that had to grow one; the task detail already had its scroll in
 * the editor scaffold.
 *
 * The property under test is the one that is easy to break and hard to see:
 * the content has to be *draggable* (a gesture needs something to drag) and it
 * must not move (a screen that jumps under the finger reads as a bug).
 *
 * The container deliberately does **not** supply the scroll: a scroll container
 * measures its content with an unbounded height, and a content that fills the
 * screen would collapse to nothing. The scroll belongs to the screen.
 */
@Tag("slow")
@Tag("slow:compose")
class RefreshScrollAnchorTest {

    private fun scope() = AutoCloseableCoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Test
    fun `a screen whose own root scrolls is pullable`() =
        runIsolatedComposeTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(0))
            val sync = FakeSyncRepository()
            val vm = RefreshViewModel(
                coordinator = RefreshCoordinator(archiver, sync, scope()),
                scope = scope(),
            )

            setContent {
                ScreenWithOwners {
                    RefreshableContent(viewModel = vm) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .testTag(CONTENT_TAG),
                        ) {
                            Text("a title")
                            Text("a row")
                        }
                    }
                }
            }

            awaitTag(CONTENT_TAG).performTouchInput {
                swipeDown(startY = 120f, endY = 620f, durationMillis = 600)
            }

            waitForIdle()

            assertEquals(1, sync.syncOnceCalls, "the pull must reach the coordinator")
        }

    @Test
    fun `the content does not move when the pull is released`() =
        runIsolatedComposeTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(0))
            val sync = FakeSyncRepository()
            val vm = RefreshViewModel(
                coordinator = RefreshCoordinator(archiver, sync, scope()),
                scope = scope(),
            )

            setContent {
                ScreenWithOwners {
                    RefreshableContent(viewModel = vm) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .testTag(CONTENT_TAG),
                        ) {
                            Text("a title")
                            Text("a row")
                        }
                    }
                }
            }

            val before = awaitTag(CONTENT_TAG).fetchSemanticsNode().positionInRoot.y

            awaitTag(CONTENT_TAG).performTouchInput {
                swipeDown(startY = 120f, endY = 620f, durationMillis = 600)
            }

            waitForIdle()

            val after = awaitTag(CONTENT_TAG).fetchSemanticsNode().positionInRoot.y
            assertEquals(before, after, 2f, "the content must not stay shifted after the pull")
        }

    private companion object {
        /** The tag on the content the gesture is performed on. */
        const val CONTENT_TAG = "refresh_anchor_content"
    }
}
