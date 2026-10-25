package com.singularity.todo.core.refresh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.NotificationHost
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext

/**
 * The refresh gesture, wrapped around a screen's content.
 *
 * One composable for every screen in scope, because the gesture has to behave
 * the same everywhere: the same indicator, the same report, the same rule
 * that a refresh does not become two.
 *
 * ## What it puts around the content
 *
 * A pull-to-refresh box and the report's host. The host is a *sibling* of the
 * box and not a child: a report that arrives while the content is being
 * dragged must not be clipped by the box, and the box's nested-scroll
 * connection must not see the host's layout.
 *
 * ## Why the report is a notice and not a dialog
 *
 * The report confirms an action the user just asked for. A modal dialog makes
 * the user dismiss it before doing anything else, which is the wrong shape
 * for a confirmation that needs no answer.
 *
 * ## The content has to scroll
 *
 * The gesture is a nested-scroll gesture: the drag has to travel up from a
 * scrollable to the box, and a screen whose content cannot scroll has nothing
 * to drag. The container does **not** supply a scrollable — it cannot, safely:
 * a scroll container measures its content with an unbounded height, and a
 * content that fills the screen would collapse to nothing.
 *
 * So the screen keeps its own scroll, on its own root, in the order the rest
 * of the codebase uses (`fillMaxSize().padding().verticalScroll()`). A screen
 * that has none yet has to grow one — the gesture needs it.
 *
 * @param modifier the modifier for the container.
 * @param viewModel the refresh ViewModel. A parameter with a `koinViewModel()`
 *   default, so a preview can pass a fake. It is null when there is no Koin
 *   scope to resolve it from — an IDE preview, which renders the screen's
 *   look and has nothing to refresh.
 * @param content the screen's content — the scrollable, in every case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableContent(
    modifier: Modifier = Modifier,
    viewModel: RefreshViewModel? = rememberRefreshViewModelOrNull(),
    content: @Composable () -> Unit,
) {
    val vm = viewModel
    if (vm == null) {
        // No Koin scope: an IDE preview. The preview renders the screen's
        // look, and a preview must not need a dependency graph — see the
        // `singularity-todo-preview-with-koin` skill.
        content()
        return
    }

    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val isRefreshing = state is RefreshUiState.Refreshing
    val pullState = rememberPullToRefreshState()

    Box(modifier = modifier) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            // Inert while a refresh runs: the gesture that started it is the
            // gesture that is already accounted for, and a second one would
            // be a second request for a thing that is already happening.
            onRefresh = {
                if (!isRefreshing) vm.onIntent(RefreshIntent.Refresh)
            },
            modifier = Modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .testTag(TestTags.REFRESH_INDICATOR),
                )
            },
        ) {
            content()
        }

        NotificationHost(
            events = vm.events,
            mapper = { it.toNotification() },
            modifier = Modifier.testTag(TestTags.REFRESH_NOTIFICATION_HOST),
        )
    }
}

/**
 * The refresh ViewModel, or null when there is no Koin to resolve it from.
 *
 * The check happens **before** the resolution and on purpose:
 *
 * - No Koin at all — an IDE preview, which has no dependency graph. The
 *   screen still renders; it just has no gesture. The Compose compiler
 *   forbids a `try`/`catch` around a composable call, so the question has to
 *   be asked before the call, not caught around it.
 * - A Koin without the binding — a real defect. It must crash loudly, and it
 *   does: nothing here swallows the resolution's failure.
 *
 * ## What "no Koin" means here
 *
 * The **global** Koin. Both shipped applications start theirs with
 * `startKoin`, so the global one is what production runs on. The scope a
 * composition carries of its own — the one `KoinIsolatedContext` installs —
 * is not consulted, because the only public way to ask for it is an API the
 * project does not use: `LocalKoinScope` is deprecated, and
 * `LocalKoinScopeContext` is marked `@KoinInternalAPI`.
 *
 * The consequence is narrow and known: a test that mounts the application
 * inside an isolated Koin scope renders the screens without the gesture. The
 * gesture itself is covered by the desktop UI test, which hands the
 * ViewModel to the container directly.
 */
@Composable
private fun rememberRefreshViewModelOrNull(): RefreshViewModel? =
    if (GlobalContext.getOrNull() == null) null else koinViewModel<RefreshViewModel>()
