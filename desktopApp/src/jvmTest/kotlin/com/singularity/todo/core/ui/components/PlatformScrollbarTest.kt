@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bar publishes, on the container it is attached to, the very scroll state it
 * draws from.
 *
 * A scrollbar draws and hit-tests but publishes no semantics of its own, so the
 * seam publishes [PlatformScrollbarSemantics.listState] / [PlatformScrollbarSemantics.columnState]
 * instead. Publishing the *instance* rather than a marker is what makes the
 * call-site contract testable at all: `rememberLazyListState()` called twice
 * publishes a state the container never scrolls, and the third test below
 * reproduces exactly that mistake to show the shape catches it.
 *
 * These tests pin the mechanism on a container built the way the screens build
 * theirs; `DesktopScrollbarFlowTest` is the screen-level check on top of it.
 */
@Tag("fast")
class PlatformScrollbarTest {

    @Test
    fun `a list that carries the bar publishes the state it draws from`() =
        runIsolatedComposeTest {
            lateinit var listState: LazyListState
            setContent {
                listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().platformVerticalScrollbar(listState),
                ) {
                    items(200) { index -> Text("Row $index") }
                }
            }

            assertSame(listState, publishedListState())
        }

    @Test
    fun `a list without the bar publishes nothing`() =
        runIsolatedComposeTest {
            setContent {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(200) { index -> Text("Row $index") }
                }
            }

            onNode(carriesListBar(), useUnmergedTree = true).assertDoesNotExist()
        }

    /**
     * The shape a mis-wired call site has, and why the published state is worth
     * having: the list scrolls one state, the bar draws from another.
     *
     * Nothing in the type system forbids this, and a marker would not show it —
     * the bar would look present and work over a state that never moves. Reading
     * the published state back and finding it still at the top, while the list
     * moved, is the difference.
     */
    @Test
    fun `a bar wired to a second state is a state the container never scrolls`() =
        runIsolatedComposeTest {
            lateinit var containerState: LazyListState
            lateinit var barState: LazyListState
            setContent {
                containerState = rememberLazyListState()
                barState = rememberLazyListState()
                LazyColumn(
                    state = containerState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag("list")
                            .platformVerticalScrollbar(barState),
                ) {
                    items(200) { index -> Text("Row $index") }
                }
            }

            onNodeWithTag("list").performScrollToIndex(120)

            val published = publishedListState()
            assertSame(barState, published)
            assertNotSame(containerState, published)
            assertTrue(containerState.firstVisibleItemIndex > 0)
            assertEquals(0, published?.firstVisibleItemIndex)
        }

    /**
     * The notes list's own tag survives the bar.
     *
     * `NotesListScreen` writes `testTag(TestTags.NOTES_LIST)` and then this
     * modifier on one node. Two `testTag`s there would collapse into a single
     * value — Compose keeps one value per key — and one of the two would silently
     * disappear. The key the bar publishes is a different key, so both live.
     */
    @Test
    fun `a container that already carries a tag keeps it and gains the bar`() =
        runIsolatedComposeTest {
            lateinit var listState: LazyListState
            setContent {
                listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag(TestTags.NOTES_LIST)
                            .platformVerticalScrollbar(listState),
                ) {
                    items(200) { index -> Text("Row $index") }
                }
            }

            // One node carries both: the tag addresses it, and reading the key
            // off that same node proves the two did not replace each other.
            val node = onNodeWithTag(TestTags.NOTES_LIST).fetchSemanticsNode()
            assertSame(listState, node.config[PlatformScrollbarSemantics.listState])
        }

    @Test
    fun `a column that carries the bar publishes the state it draws from`() =
        runIsolatedComposeTest {
            lateinit var scrollState: ScrollState
            setContent {
                scrollState = rememberScrollState()
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .platformVerticalScrollbar(scrollState),
                ) {
                    repeat(200) { index -> Text("Row $index") }
                }
            }

            assertSame(scrollState, publishedColumnState())
        }
}

private fun carriesListBar(): SemanticsMatcher =
    SemanticsMatcher("publishes ${PlatformScrollbarSemantics.listState.name}") { node ->
        node.config.getOrNull(PlatformScrollbarSemantics.listState) != null
    }

private fun SemanticsNodeInteractionsProvider.publishedListState(): LazyListState? =
    onNode(carriesListBar(), useUnmergedTree = true)
        .fetchSemanticsNode()
        .config
        .getOrNull(PlatformScrollbarSemantics.listState)

private fun SemanticsNodeInteractionsProvider.publishedColumnState(): ScrollState? =
    onNode(
        SemanticsMatcher("publishes ${PlatformScrollbarSemantics.columnState.name}") { node ->
            node.config.getOrNull(PlatformScrollbarSemantics.columnState) != null
        },
        useUnmergedTree = true,
    ).fetchSemanticsNode().config.getOrNull(PlatformScrollbarSemantics.columnState)
