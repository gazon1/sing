package com.singularity.todo.feature.projects

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.newRecentEmojiStore
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The project icon picker's recently used emoji.
 *
 * ## What is under test
 *
 * The sheet remembers the emoji the user picked and offers the last ones back at
 * the top of the next opening — with no history there is no "Recent" section at
 * all, because an empty heading is a blank the user has to read past every time.
 *
 * The history itself is covered where it lives (the rule and the store); this is
 * about the sheet: what it draws, and what it records when an emoji is picked.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class IconPickerRecentEmojiTest {
    @Test
    fun `the sheet has no recent section while nothing was picked`() =
        runIsolatedComposeTest {
            val store = newRecentEmojiStore()

            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        onPick = {},
                        onDismiss = {},
                        recentEmojiStore = store,
                    )
                }
            }

            assertTagNotExists(TestTags.PROJECT_ICON_RECENT_SECTION)
        }

    @Test
    fun `a recently picked emoji is offered back`() =
        runIsolatedComposeTest {
            val store = newRecentEmojiStore()
            // Not one of the emoji the sheet offers itself, so the text below can
            // only come from the recent row.
            store.record("🦄")

            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        onPick = {},
                        onDismiss = {},
                        recentEmojiStore = store,
                    )
                }
            }

            assertTagDisplayed(TestTags.PROJECT_ICON_RECENT_SECTION)
            assertTextDisplayed("🦄")
        }

    @Test
    fun `picking an emoji puts it in the history`() =
        runIsolatedComposeTest {
            val store = newRecentEmojiStore()
            val picked = CompletableDeferred<String?>()

            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        onPick = { picked.complete(it) },
                        onDismiss = {},
                        recentEmojiStore = store,
                    )
                }
            }

            clickText("🎯")

            // The wait is real time, not the test scheduler's virtual time: the
            // write goes to a file, and a virtual clock would expire long
            // before the file was written.
            waitUntil(timeoutMillis = TIMEOUT_MS) { picked.isCompleted }
            assertEquals("🎯", picked.await(), "the emoji was picked")

            val recorded =
                withContext(Dispatchers.Default) {
                    withTimeout(TIMEOUT_MS) {
                        store.recent.first { it.isNotEmpty() }
                    }
                }
            assertEquals(listOf("🎯"), recorded, "and it was recorded")
        }

    private companion object {
        /** Generous upper bound; the write lands in milliseconds. */
        const val TIMEOUT_MS = 5_000L
    }
}
