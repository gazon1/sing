@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The desktop scrollbar is attached to the container it is asked for.
 *
 * The bar draws and hit-tests but publishes no semantics of its own, so the
 * seam publishes [PLATFORM_SCROLLBAR_TEST_TAG] on the container instead. That
 * is what makes the second test below a real check rather than a tautology: the
 * tag is asserted to be absent from a list that never asked for the bar, so a
 * list that loses the modifier fails the first test.
 *
 * What this does not prove is that the state handed to the bar is the state the
 * container scrolls; a second `rememberLazyListState()` would carry the tag the
 * same way. That part stays a call-site contract, argued in the seam's own
 * documentation.
 */
@Tag("fast")
class PlatformScrollbarTest {
    @Test
    fun `a list that carries the bar publishes the tag`() =
        runIsolatedComposeTest {
            setContent {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().platformVerticalScrollbar(listState),
                ) {
                    items(200) { index -> Text("Row $index") }
                }
            }

            onNodeWithTag(PLATFORM_SCROLLBAR_TEST_TAG).assertExists()
        }

    @Test
    fun `a list without the bar does not publish the tag`() =
        runIsolatedComposeTest {
            setContent {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(200) { index -> Text("Row $index") }
                }
            }

            onNodeWithTag(PLATFORM_SCROLLBAR_TEST_TAG).assertDoesNotExist()
        }
}
