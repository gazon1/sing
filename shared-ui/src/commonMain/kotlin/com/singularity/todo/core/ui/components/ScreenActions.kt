package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Holder for the current screen's TopAppBar actions on platforms where the
 * shell owns the only bar (Android). Screens publish their actions through the
 * platform-aware [TopAppBar]/[BackTopAppBar] wrappers; the Android shell reads
 * [actions] and renders them in its own bar.
 */
class ScreenActionsHolder {
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)

    // The bar a screen owns. On Android the shell owns the ONLY bar, so a
    // screen that draws its own header (the calendar's month, the settings
    // title) publishes that header here and the shell draws it INSTEAD of its
    // own title and back button. Two bars would be two bars.
    //
    // These are derived from [publishedBars], never written by the shell: a
    // screen keeps the bar for as long as it is composed, and leaving takes
    // down only what that screen published.
    var title: (@Composable () -> Unit)? by mutableStateOf(null)
        private set
    var navigationIcon: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    // Whether a composed screen currently publishes a title — the shell draws
    // that title instead of its own destination name.
    var override: Boolean by mutableStateOf(false)
        private set

    // Selection-mode state for the Android shell bar: when set, the shell shows
    // "N selected" with an exit-selection back button instead of the normal bar.
    var selectionTitle: String? by mutableStateOf(null)
    var onExitSelection: (() -> Unit)? by mutableStateOf(null)

    // One entry per composed screen that publishes a bar, in composition
    // order. The slot shows the last entry, so a screen that leaves reveals
    // the screen underneath it instead of leaving the bar empty — which is what
    // makes a nested screen's pop restore the previous title even when that
    // screen does not recompose.
    private val publishedBars = mutableListOf<PublishedBar>()

    /**
     * Publishes [content] as the bar's title on behalf of [publisher].
     *
     * The publisher is an identity token owned by the publishing screen (see
     * [ShellOverride] and the `overrideShell` path of [TopAppBar]); its
     * disposal releases the entry it owns. Republishing updates an existing
     * entry in place, so a background screen that recomposes cannot jump above
     * the screen on screen.
     */
    fun publishTitle(
        publisher: Any,
        content: @Composable () -> Unit,
        navigationIcon: (@Composable () -> Unit)?,
    ) {
        val entry = PublishedBar(publisher, content, navigationIcon)
        val index = publishedBars.indexOfFirst { it.publisher === publisher }
        if (index >= 0) {
            publishedBars[index] = entry
        } else {
            publishedBars.add(entry)
        }
        publishTopBar()
    }

    /**
     * Takes down [publisher]'s entry, if it still has one.
     *
     * A late disposal from the screen the user has left removes only that
     * screen's entry: the title another screen published stays.
     */
    fun releaseTitle(publisher: Any) {
        val removed = publishedBars.removeAll { it.publisher === publisher }
        if (removed) publishTopBar()
    }

    private fun publishTopBar() {
        val top = publishedBars.lastOrNull()
        title = top?.title
        navigationIcon = top?.navigationIcon
        override = top != null
    }

    private class PublishedBar(
        val publisher: Any,
        val title: @Composable () -> Unit,
        val navigationIcon: (@Composable () -> Unit)?,
    )
}

/**
 * Drops the slots a screen leaves behind when the top-level route changes: the
 * action row and the selection mode.
 *
 * The published title is deliberately not cleared here. This effect runs after
 * the incoming screen has composed, so clearing now would erase the title that
 * screen has just published; each publisher releases its own title when it
 * leaves composition instead (see [ShellOverride]).
 */
@Composable
fun ScreenActionsHolder.ClearStaleSlotsOnNavigation(routeKey: Any?) {
    LaunchedEffect(routeKey) {
        actions = null
        selectionTitle = null
        onExitSelection = null
    }
}

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }
