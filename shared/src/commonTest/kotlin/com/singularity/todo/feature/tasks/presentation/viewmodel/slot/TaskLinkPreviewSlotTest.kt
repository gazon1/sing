package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.linkpreview.LinkPreview
import com.singularity.todo.core.linkpreview.LinkPreviewRepository
import com.singularity.todo.core.linkpreview.LinkPreviewState
import com.singularity.todo.core.text.Revisioned
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailDraft
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

        override fun observe(url: String?): Flow<LinkPreviewState> =
            if (url == null) flowOf(LinkPreviewState.None) else flowOf(stored)

        override suspend fun refresh(url: String): LinkPreviewState {
            refreshed += url
            return stored
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
}
