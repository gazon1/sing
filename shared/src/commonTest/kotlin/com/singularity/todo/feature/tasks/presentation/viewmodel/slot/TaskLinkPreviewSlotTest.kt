package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.linkpreview.LinkPreview
import com.singularity.todo.core.linkpreview.LinkPreviewRepository
import com.singularity.todo.core.linkpreview.LinkPreviewState
import com.singularity.todo.core.text.Revisioned
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The title's address drives the card, and hiding it touches nothing else.
 *
 * The repository is a fake, so the tests can say both what was shown and what the
 * network would have been asked for — the second is the caching requirement.
 */
@Tag("fast")
class TaskLinkPreviewSlotTest {
    /** Records refreshes and hides, and answers `observe` from what the test stored. */
    private class FakePreviews : LinkPreviewRepository {
        val refreshed = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        var stored: LinkPreviewState = LinkPreviewState.Loading

        /** What [refresh] answers, when it differs from [stored]. */
        var refreshResult: LinkPreviewState? = null

        /** Run when [refresh] is called, so a test can change the title mid-flight. */
        var onRefresh: suspend () -> Unit = {}

        /** Answers per address, so a test can say what the *new* address shows. */
        val byUrl = mutableMapOf<String, LinkPreviewState>()

        override fun observe(url: String?): Flow<LinkPreviewState> =
            when {
                url == null -> flowOf(LinkPreviewState.None)
                else -> flowOf(byUrl[url] ?: stored)
            }

        override suspend fun refresh(url: String): LinkPreviewState {
            refreshed += url
            val answer = refreshResult ?: stored
            onRefresh()
            return answer
        }

        override suspend fun hide(url: String) {
            hidden += url
        }

        override suspend fun forget(url: String) = Unit
    }

    private fun draft(title: String) =
        MutableStateFlow(
            TaskDetailDraft(
                title = Revisioned.initial(title),
                description = Revisioned.initial(""),
                originalTitle = title,
                originalDescription = "",
                initialized = true,
            ),
        )

    private fun preview(url: String) =
        LinkPreview(
            url = url,
            siteName = "Example",
            title = "A page",
            description = null,
            imageUrl = null,
            fetchedAt = 0L,
        )

    @Test
    fun `a title with an address is refreshed`() =
        runTest {
            val previews = FakePreviews()
            val slot =
                TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("read https://example.com/a"))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.RefreshLinkPreview)
            runCurrent()
            assertEquals(listOf("https://example.com/a"), previews.refreshed)
        }

    @Test
    fun `a title with no address refreshes nothing`() =
        runTest {
            val previews = FakePreviews()
            val slot = TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("Buy milk"))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.RefreshLinkPreview)
            runCurrent()
            assertTrue(previews.refreshed.isEmpty())
        }

    @Test
    fun `an internal link alone refreshes nothing`() =
        runTest {
            val previews = FakePreviews()
            val slot = TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("see task://abc"))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.RefreshLinkPreview)
            runCurrent()
            assertTrue(previews.refreshed.isEmpty())
        }

    @Test
    fun `hiding dismisses the address the card is showing`() =
        runTest {
            val previews = FakePreviews()
            previews.stored = LinkPreviewState.Ready(preview("https://example.com/a"))
            val slot =
                TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("read https://example.com/a"))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.HideLinkPreview)
            runCurrent()
            assertEquals(listOf("https://example.com/a"), previews.hidden)
        }

    @Test
    fun `hiding while the card is only loading does nothing`() =
        runTest {
            val previews = FakePreviews()
            val slot =
                TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("read https://example.com/a"))
            runCurrent()
            // There is no card to dismiss yet — the state is Loading — and a dismissal
            // of a card nobody can see is not a decision the user made.
            slot.onIntent(TaskDetailIntent.Domain.HideLinkPreview)
            runCurrent()
            assertTrue(previews.hidden.isEmpty())
        }

    @Test
    fun `hiding with nothing shown does nothing`() =
        runTest {
            val previews = FakePreviews()
            val slot = TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("Buy milk"))
            runCurrent()
            slot.onIntent(TaskDetailIntent.Domain.HideLinkPreview)
            runCurrent()
            assertTrue(previews.hidden.isEmpty())
        }

    @Test
    fun `the observed state is what the repository holds for the title's address`() =
        runTest {
            val previews = FakePreviews()
            previews.stored = LinkPreviewState.Ready(preview("https://example.com/a"))
            val slot =
                TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("read https://example.com/a"))
            runCurrent()
            assertEquals(previews.stored, slot.state.value)
        }

    @Test
    fun `a title without an address shows no card`() =
        runTest {
            val previews = FakePreviews()
            val slot = TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft("Buy milk"))
            runCurrent()
            assertEquals(LinkPreviewState.None, slot.state.value)
        }

    @Test
    fun `an answer for an address the title no longer names is not shown`() =
        runTest {
            // The title names one address when the fetch starts and another when it
            // finishes. The card describes the title *now*, so the late answer for the
            // first address must not replace what the collector is showing. The fetch is
            // held open until the title has moved on, which is what makes the two orders
            // distinguishable — otherwise whichever coroutine runs last wins, and a broken
            // guard would pass.
            val previews = FakePreviews()
            previews.stored = LinkPreviewState.Ready(preview("https://example.com/a"))
            val draft = draft("read https://example.com/a")
            val slot = TaskLinkPreviewSlot(previews, testSlotScope(backgroundScope), draft)
            runCurrent()

            val gate = CompletableDeferred<Unit>()
            previews.refreshResult = LinkPreviewState.Ready(preview("https://example.com/a"))
            previews.onRefresh = { gate.await() }
            slot.onIntent(TaskDetailIntent.Domain.RefreshLinkPreview)
            runCurrent()

            // The title moves to an address that has no card of its own.
            previews.byUrl["https://example.com/b"] = LinkPreviewState.None
            draft.value = draft.value.copy(title = Revisioned.initial("now https://example.com/b"))
            runCurrent()
            assertEquals(LinkPreviewState.None, slot.state.value)

            // Now the fetch for the *old* address lands.
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                LinkPreviewState.None,
                slot.state.value,
                "a card for the address the title no longer names must not be shown",
            )
        }
}
