package com.singularity.todo.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds a draft value together with the "checkpoint" (last known-saved value)
 * it is compared against, and answers the one question a `DraftState` exists
 * for: **is the draft different from what's saved?**
 *
 * This factors the `baseline` bookkeeping that used to live as a private var
 * inside [DraftMviViewModel] into its own unit, testable without coroutines
 * or a `TestScope`. It intentionally does **not** grow into an undo/redo
 * stack (see the discussion of `Undoable<S>` this review considered and
 * rejected): `discard()` here means "go back to the last checkpoint", not
 * "undo the last edit" — those are different UX and conflating them would
 * make `discard()` behave surprisingly right after `open()`.
 *
 * Four operations, each with one job:
 * - [edit] — mutate the current value; checkpoint untouched (this is what
 *   autosave does: it persists `current` without ever calling [checkpoint]).
 * - [discard] — revert `current` back to the checkpoint.
 * - [checkpoint] — declare the current value saved (call this after a
 *   successful explicit `save()`, never after autosave).
 * - [open] — switch to editing a different entity; checkpoint and current
 *   move together, since a freshly opened entity starts clean.
 *
 * @param S The draft's value type.
 * @param initial Starting value, used as both `current` and the initial checkpoint.
 */
class DraftState<S>(initial: S) {

    private val _current = MutableStateFlow(initial)

    /** Reactive view of the current draft value. */
    val current: StateFlow<S> = _current.asStateFlow()

    private var checkpointValue: S = initial

    /** Snapshot of the current draft value. Prefer [current] for reactive consumers. */
    val value: S get() = _current.value

    /** True when [value] differs from the last [checkpoint]. */
    val isDirty: Boolean get() = _current.value != checkpointValue

    /** Mutates the current value via [reducer]. Does not move the checkpoint. */
    fun edit(reducer: (S) -> S) {
        _current.update(reducer)
    }

    /** Reverts [current] to the last checkpoint, discarding any edits since. */
    fun discard() {
        _current.value = checkpointValue
    }

    /**
     * Declares the current value as saved: future [isDirty] checks and
     * future [discard] calls are relative to this new checkpoint.
     *
     * Call this after a successful explicit save — never from autosave,
     * which must persist `current` silently without moving the checkpoint
     * (see [DraftMviViewModel]'s autosave-is-silent contract).
     */
    fun checkpoint() {
        checkpointValue = _current.value
    }

    /**
     * Switches to editing a different entity. Checkpoint and current move
     * together, since the newly opened [value] starts out clean (not dirty).
     */
    fun open(value: S) {
        checkpointValue = value
        _current.value = value
    }
}
