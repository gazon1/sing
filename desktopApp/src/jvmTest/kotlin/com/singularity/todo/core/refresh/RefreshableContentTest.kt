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
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeCompletedTaskArchiver
import com.singularity.todo.test.fakes.FakeSyncRepository
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop UI tests for [RefreshableContent].
 *
 * The gesture is driven for real — a pointer drag, not a call to the
 * ViewModel — because the whole point of the feature is that a pull on the
 * screen reaches the coordinator.
 */
@Tag("slow")
@Tag("slow:compose")
class RefreshableContentTest {

    private fun scope() = AutoCloseableCoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Test
    fun `the gesture reaches the coordinator and the report appears in the notification host`() =
        runIsolatedComposeTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(2))
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
                            repeat(40) { Text("row $it") }
                        }
                    }
                }
            }

            assertTagExists(TestTags.REFRESH_INDICATOR)

            awaitTag(CONTENT_TAG).performTouchInput {
                swipeDown(startY = 120f, endY = 620f, durationMillis = 600)
            }

            waitForIdle()

            assertEquals(1, archiver.archiveCalls, "the pull must archive exactly once")
            assertEquals(1, sync.syncOnceCalls, "the pull must synchronise exactly once")

            awaitText("Synced. 2 tasks moved to the archive.")
        }

    private companion object {
        /** The tag on the scrollable that the gesture is performed on. */
        const val CONTENT_TAG = "refresh_test_content"
    }
}
