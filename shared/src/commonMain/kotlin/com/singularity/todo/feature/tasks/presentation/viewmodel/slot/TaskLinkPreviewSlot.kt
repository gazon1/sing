package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.linkpreview.LinkPreviewRepository
import com.singularity.todo.core.linkpreview.LinkPreviewState
import com.singularity.todo.core.linkpreview.PreviewUrls
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.domain.state.TaskLinkPreviewIntent
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The link preview under the task's title.
 *
 * ## What it reads
 *
 * The address comes from the **title draft**, not from the saved title: the preview
 * follows the title as the user types it, so pasting a link shows the card without a
 * save round trip. [PreviewUrls.primaryIn] is the one rule for which address that is.
 *
 * ## Why opening the screen fetches, and recomposing it does not
 *
 * [state] is fed by the repository's cache read, which is a flow over a preference —
 * free to collect and free to re-collect. The page is fetched once, from
 * [onIntent]`(Refresh)`, which the screen calls from a `LaunchedEffect` keyed by the
 * address. A recomposition re-reads the cache and asks the network for nothing.
 *
 * ## What a failed fetch looks like
 *
 * [LinkPreviewState.Unavailable] — the card offers nothing and the title keeps its
 * address. There is no error event: the user did not ask for this preview, so a page
 * that would not load is not a failure worth interrupting them over.
 *
 * @param previews the repository, which owns the cache and the guarded fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLinkPreviewSlot(
    private val previews: LinkPreviewRepository,
    private val scope: AutoCloseableCoroutineScope,
    private val draftFlow: StateFlow<TaskDetailDraft>,
) : FeatureSlot<LinkPreviewState, TaskLinkPreviewIntent> {
    private val _state = MutableStateFlow<LinkPreviewState>(LinkPreviewState.None)
    override val state: StateFlow<LinkPreviewState> = _state.asStateFlow()

    /** The title as the user is typing it — the text a preview follows. */
    private val titleFlow: Flow<String> = draftFlow.map { it.title.value }

    /**
     * The card follows the title's address.
     *
     * Started as a field, not from an `init` block: a coroutine launched from `init` can
     * observe the object before it is finished, and the rule that forbids it is the one
     * this field exists to satisfy.
     */
    private val collector =
        scope.launch {
            titleFlow
                .map { PreviewUrls.primaryIn(it) }
                .distinctUntilChanged()
                .flatMapLatest { url -> previews.observe(url) }
                .collect { _state.value = it }
        }

    override fun onIntent(intent: TaskLinkPreviewIntent) {
        when (intent) {
            TaskDetailIntent.Domain.RefreshLinkPreview -> {
                val url = PreviewUrls.primaryIn(draftFlow.value.title.value) ?: return
                scope.launch {
                    val state = previews.refresh(url)
                    // The title may have changed while the page was in flight. The card
                    // describes *the address in the title now*, so a late answer for an
                    // address the title no longer names must not be put on screen — the
                    // collector already shows the newer one, and this would overwrite it
                    // with a card for a link the user has since edited away.
                    if (PreviewUrls.primaryIn(draftFlow.value.title.value) == url) {
                        _state.value = state
                    }
                }
            }

            TaskDetailIntent.Domain.HideLinkPreview -> {
                // The address the card is *showing*, read from the state rather than from
                // a field the collector also writes: a field written by two coroutines is
                // two answers to "which address is on screen", and a hide that lands on
                // the wrong one is a dismissal the user did not make.
                val url = (_state.value as? LinkPreviewState.Ready)?.preview?.url ?: return
                scope.launch { previews.hide(url) }
            }
        }
    }
}
