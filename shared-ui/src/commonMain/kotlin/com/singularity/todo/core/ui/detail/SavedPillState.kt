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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 * ends. [pulse] cancels the running timer first, so the pill stays up for a full window
 * after the *last* write and no longer. That is also why there is one implementation: the
 * bug was written twice when the idiom was duplicated.
 *
 * @param scope owns the timer. It is the caller's scope, so pulses stop when the screen
 *        does; the holder never outlives it.
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

    private var timer: Job? = null

    /**
     * A write landed: raise the pill and restart its window.
     *
     * Safe to call from an event collector or from a UI handler. Two pulses in quick
     * succession leave one live timer, belonging to the later one.
     */
    fun pulse() {
        timer?.cancel()
        visible = true
        timer =
            scope.launch {
                delay(window)
                visible = false
            }
    }
}

/**
 * A [SavedPillState] fed by [pulses] — for a screen whose event flow has one consumer
 * and no other reason to be collected here.
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
