package com.singularity.todo.core.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.singularity.todo.core.platform.TimeConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * "Saved" visibility for a screen that autosaves, plus the one timer that takes it down.
 *
 * Both autosaving details — the note editor and the task detail — show the same
 * [SavedIndicator] for one autosave window after a write lands. The timer lives here
 * rather than inline at each call site because the obvious inline version races:
 *
 * ```kotlin
 * onPulse { scope.launch { visible = true; delay(window); visible = false } }
 * ```
 *
 * launches an uncancelled coroutine *per pulse*. A second write 200 ms after the first
 * gets a second timer, and the first timer's deadline hides the pill in the middle of the
 * second write's window — the newest write's indicator flickers off before its window
 * ends. [pulse] restarts the running window instead, so the pill stays up for a full
 * window after the *last* write and no longer. That is also why there is one
 * implementation: the bug was written twice when the idiom was duplicated.
 *
 * ## Who writes [visible], and on which dispatcher
 *
 * `visible` is Compose snapshot state read from composition, so which thread writes it
 * matters. [pulse] therefore never touches `visible` itself: it hands the event to a
 * conflated channel, and the single collector this holder starts is the only writer,
 * running on [scope]'s dispatcher. Two consequences, both wanted:
 *
 *  * [pulse] is callable from anywhere — an event collector, a UI handler, a background
 *    thread — without the caller having to know which dispatcher it is on. That is the
 *    guarantee the inline idiom above only appeared to offer.
 *  * Every mutation of [visible] happens on one dispatcher, so a pulse can never
 *    interleave with the timer it restarts.
 *
 * The flip side is that the caller supplies [scope], and *its* dispatcher is the one that
 * ends up touching the state: pass a composition scope (as [rememberSavedPillState] does)
 * and the writes land on the UI thread, which is what composition requires. Pass a
 * background scope and the writes land there — the holder cannot fix that for the caller,
 * so it says so here instead of promising otherwise.
 *
 * The raise is therefore one dispatch after the pulse rather than instantaneous. That is a
 * frame in the UI and invisible to the user; a test that wants to observe it runs the
 * dispatcher first.
 *
 * @param scope owns the timer and supplies the dispatcher the state is written on. It is
 *        the caller's scope, so pulses stop when the screen does; the holder never
 *        outlives it.
 * @param window how long the pill stays up. The autosave window by default: the pill is
 *        saying "the thing you were just typing is on disk", and a pause of that length
 *        is what produced the write.
 */
@Stable
class SavedPillState(
    private val scope: CoroutineScope,
    private val window: Duration = TimeConstants.AutoSaveDebounceMs,
) {
    /** Whether the pill should be drawn. Read from composition. */
    var visible by mutableStateOf(false)
        private set

    /**
     * Pulses waiting to become windows.
     *
     * A conflated channel rather than a `SharedFlow`: a pulse that arrives before the
     * collector has been scheduled must still be honoured (a replayed-0 `SharedFlow` with
     * no subscriber yet would drop it), and only the newest pulse matters because they all
     * say the same thing.
     */
    private val pulses = Channel<Unit>(Channel.CONFLATED)

    /**
     * The single writer of [visible]: one window at a time, restarted by each pulse.
     *
     * A field initializer rather than an `init` block, and the difference is the
     * point: the collector is launched once the object is fully built, so it can
     * never observe a half-constructed holder.
     *
     * `collectLatest`, not a plain receive loop: a pulse arriving mid-window cancels
     * the window in flight and starts a fresh one, which is the whole point.
     */
    private val pulseWindow: Job =
        scope.launch {
            pulses
                .receiveAsFlow()
                .collectLatest {
                    visible = true
                    delay(window)
                    visible = false
                }
        }

    /**
     * A write landed: raise the pill and restart its window.
     *
     * Safe to call from an event collector or from a UI handler, on any thread: this only
     * offers to the channel. Two pulses in quick succession leave one live window,
     * belonging to the later one.
     */
    fun pulse() {
        // The window is only alive while the collector is: a pulse offered to a
        // cancelled scope would wait in the channel for a reader that never comes.
        if (!pulseWindow.isActive) return
        pulses.trySend(Unit)
    }
}

/**
 * A [SavedPillState] fed by [pulses] — for a screen whose event flow has one consumer
 * and no other reason to be collected here.
 *
 * **[pulses] must have a stable identity across recompositions.** The effect below is
 * keyed on it, so a caller that builds the flow inline —
 * `rememberSavedPillState(events.filterIsInstance<SavedPulse>())` — hands over a *new*
 * Flow object on every recomposition. The effect then cancels and restarts the collector
 * each time the screen recomposes (on every keystroke, for an editor), and a pulse that
 * lands while the collector is being torn down and re-subscribed is dropped: the pill
 * silently never appears, with nothing in the log to say why. `remember` the derived flow
 * at the call site and pass that.
 */
@Composable
fun rememberSavedPillState(pulses: Flow<*>): SavedPillState {
    val state = rememberSavedPillState()
    LaunchedEffect(pulses, state) {
        pulses.collect { state.pulse() }
    }
    return state
}

/**
 * A [SavedPillState] driven by explicit [SavedPillState.pulse] calls.
 *
 * This is the form a screen needs when it already collects a channel of events for other
 * reasons: a channel has one consumer, so a second collector here would steal the events
 * the rest of the screen is waiting for. The screen's single collector calls `pulse()`
 * and the timer stays in one place.
 */
@Composable
fun rememberSavedPillState(): SavedPillState {
    val scope = rememberCoroutineScope()
    return remember(scope) { SavedPillState(scope) }
}
