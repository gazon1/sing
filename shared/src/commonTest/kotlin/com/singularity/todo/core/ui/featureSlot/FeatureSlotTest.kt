package com.singularity.todo.core.ui.featureSlot

import com.singularity.todo.core.ui.MviIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ── Test fixtures ──────────────────────────────────────────────────────────

private data class SlotState(val count: Int = 0, val label: String = "")

private sealed interface SlotIntent : MviIntent {
    data class Increment(val by: Int) : SlotIntent
    data class Rename(val label: String) : SlotIntent
}

/** Minimal conforming slot — the shape every real slot in the codebase follows. */
private class CounterSlot : FeatureSlot<SlotState, SlotIntent> {
    private val _state = MutableStateFlow(SlotState())
    override val state: StateFlow<SlotState> = _state.asStateFlow()
    val handled = mutableListOf<SlotIntent>()

    override fun onIntent(intent: SlotIntent) {
        handled += intent
        when (intent) {
            is SlotIntent.Increment -> _state.update { it.copy(count = it.count + intent.by) }
            is SlotIntent.Rename -> _state.update { it.copy(label = intent.label) }
        }
    }
}

@Tag("fast")
class FeatureSlotTest {

    @Test
    fun `state has a value before any intent`() {
        val slot = CounterSlot()
        assertEquals(SlotState(count = 0, label = ""), slot.state.value)
    }

    @Test
    fun `onIntent mutates state`() {
        val slot = CounterSlot()
        slot.onIntent(SlotIntent.Increment(3))
        assertEquals(3, slot.state.value.count)
    }

    @Test
    fun `every intent reaches the slot it was dispatched to`() {
        val slot = CounterSlot()
        slot.onIntent(SlotIntent.Increment(1))
        slot.onIntent(SlotIntent.Rename("done"))

        assertEquals(2, slot.handled.size)
        assertEquals(SlotIntent.Rename("done"), slot.handled[1])
        assertEquals("done", slot.state.value.label)
    }

    @Test
    fun `state is readable as a StateFlow so a coordinator always has a current value`() {
        val slot: FeatureSlot<SlotState, SlotIntent> = CounterSlot()
        slot.onIntent(SlotIntent.Increment(7))
        // A StateFlow replays its latest value to a late subscriber, which is what lets a
        // coordinator merge slot states without seeding them itself.
        assertTrue(slot.state is StateFlow<*>)
        assertEquals(7, slot.state.value.count)
    }
}
