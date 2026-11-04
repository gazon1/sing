package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf

/**
 * Holder for the current screen's TopAppBar actions on platforms where the
 * shell owns the only bar (Android). Screens publish their actions through the
 * platform-aware [TopAppBar]/[BackTopAppBar] wrappers; the Android shell reads
 * [actions] and renders them in its own bar.
 *
 * Every published slot is owned. A publisher publishes with a token it keeps
 * for the life of its composition and takes down only its own publication, so
 * two publishers on one screen — a [TopAppBar] and a [ShellOverride], or two
 * bars — cannot clear each other's work. Several publications may coexist on a
 * slot: the most recent one is what the shell reads, and taking it down makes
 * the previous publication effective again. The slot is empty only when the
 * last publisher has left.
 */
class ScreenActionsHolder {
    private val actionsSlot = OwnedSlot<(@Composable RowScope.() -> Unit)>()
    private val titleSlot = OwnedSlot<(@Composable () -> Unit)>()
    private val navigationIconSlot = OwnedSlot<(@Composable () -> Unit)?>()
    private val overrideSlot = OwnedSlot<Boolean>()
    private val selectionTitleSlot = OwnedSlot<String?>()
    private val onExitSelectionSlot = OwnedSlot<(() -> Unit)?>()

    // Every slot once, so `clear()` cannot forget one that is added later.
    private val slots =
        listOf<OwnedSlot<*>>(
            actionsSlot,
            titleSlot,
            navigationIconSlot,
            overrideSlot,
            selectionTitleSlot,
            onExitSelectionSlot,
        )

    val actions: (@Composable RowScope.() -> Unit)?
        get() = actionsSlot.value

    // The bar a screen owns. On Android the shell owns the ONLY bar, so a
    // screen that draws its own header (the calendar's month, the settings
    // title) publishes that header here and the shell draws it INSTEAD of its
    // own title and back button. Two bars would be two bars.
    //
    // `null` means "the screen has no opinion here", and the shell keeps what
    // it has. That is not the same as `override`: a screen that publishes a
    // title and no navigation icon wants its title in the bar and the shell's
    // own back button — the shell's back button knows where the shell is.
    val title: (@Composable () -> Unit)?
        get() = titleSlot.value
    val navigationIcon: (@Composable () -> Unit)?
        get() = navigationIconSlot.value

    // Whether the published title and navigation icon replace the shell's own.
    val override: Boolean
        get() = overrideSlot.value ?: false

    // Selection-mode state for the Android shell bar: when set, the shell shows
    // "N selected" with an exit-selection back button instead of the normal bar.
    val selectionTitle: String?
        get() = selectionTitleSlot.value
    val onExitSelection: (() -> Unit)?
        get() = onExitSelectionSlot.value

    /**
     * Publishes the action slots on behalf of [owner]. Republishing replaces
     * that owner's previous entry and makes it the effective publication.
     */
    fun publishActions(
        owner: Any,
        actions: @Composable RowScope.() -> Unit,
        selectionTitle: String?,
        onExitSelection: (() -> Unit)?,
    ) {
        actionsSlot.publish(owner, actions)
        selectionTitleSlot.publish(owner, selectionTitle)
        onExitSelectionSlot.publish(owner, onExitSelection)
    }

    /** Takes down [owner]'s action publication, and nothing else. */
    fun withdrawActions(owner: Any) {
        actionsSlot.withdraw(owner)
        selectionTitleSlot.withdraw(owner)
        onExitSelectionSlot.withdraw(owner)
    }

    /**
     * Publishes a bar override on behalf of [owner]: [title] and
     * [navigationIcon] replace the shell's own until this owner withdraws.
     */
    fun publishBar(
        owner: Any,
        title: @Composable () -> Unit,
        navigationIcon: (@Composable () -> Unit)?,
    ) {
        titleSlot.publish(owner, title)
        navigationIconSlot.publish(owner, navigationIcon)
        overrideSlot.publish(owner, true)
    }

    /** Takes down [owner]'s bar publication, restoring any earlier one. */
    fun withdrawBar(owner: Any) {
        titleSlot.withdraw(owner)
        navigationIconSlot.withdraw(owner)
        overrideSlot.withdraw(owner)
    }

    /**
     * Clears every publication. The Android shell calls this when the
     * top-level route changes, so a screen's stale bar and actions cannot
     * outlive it.
     */
    fun clear() {
        slots.forEach { it.clear() }
    }
}

/**
 * A stack of publications for one slot. Owners are compared by identity, so a
 * publisher cannot take down another publisher's entry by accident, and an
 * entry that has already been taken down cannot clear a newer one.
 */
private class OwnedSlot<T> {
    private val publications = mutableStateListOf<Publication<T>>()

    val value: T?
        get() = publications.lastOrNull()?.value

    fun publish(owner: Any, published: T) {
        publications.removeAll { it.owner === owner }
        publications.add(Publication(owner, published))
    }

    fun withdraw(owner: Any) {
        publications.removeAll { it.owner === owner }
    }

    fun clear() {
        publications.clear()
    }

    private class Publication<T>(val owner: Any, val value: T)
}

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }
