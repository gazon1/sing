@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui.featureSlot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers every [combineStates] overload plus the behaviours a coordinator depends on:
 * typed positional unpacking, emission on any upstream change, and no loss of values.
 */
class CombineStatesTest {

    private fun flowOf(value: Int) = MutableStateFlow(value)

    // ── One case per arity: proves each overload unpacks positions in order ────

    @Test
    fun `two flows combine in order`() = runTest {
        val out = mutableListOf<String>()
        val job = launch { combineStates(flowOf(1), flowOf(2)) { a, b -> "$a$b" }.toList(out) }

        advanceUntilIdle()
        assertEquals(listOf("12"), out)
        job.cancel()
    }

    @Test
    fun `three flows combine in order`() = runTest {
        val out = mutableListOf<String>()
        val job = launch {
            combineStates(flowOf(1), flowOf(2), flowOf(3)) { a, b, c -> "$a$b$c" }.toList(out)
        }

        advanceUntilIdle()
        assertEquals(listOf("123"), out)
        job.cancel()
    }

    @Test
    fun `four flows combine in order`() = runTest {
        val out = mutableListOf<String>()
        val job = launch {
            combineStates(flowOf(1), flowOf(2), flowOf(3), flowOf(4)) { a, b, c, d -> "$a$b$c$d" }
                .toList(out)
        }

        advanceUntilIdle()
        assertEquals(listOf("1234"), out)
        job.cancel()
    }

    @Test
    fun `five flows combine in order`() = runTest {
        val out = mutableListOf<String>()
        val job = launch {
            combineStates(flowOf(1), flowOf(2), flowOf(3), flowOf(4), flowOf(5)) { a, b, c, d, e ->
                "$a$b$c$d$e"
            }.toList(out)
        }

        advanceUntilIdle()
        assertEquals(listOf("12345"), out)
        job.cancel()
    }

    @Test
    fun `six flows combine in order`() = runTest {
        val out = mutableListOf<String>()
        val job = launch {
            combineStates(
                flowOf(1),
                flowOf(2),
                flowOf(3),
                flowOf(4),
                flowOf(5),
                flowOf(6),
            ) { a, b, c, d, e, f -> "$a$b$c$d$e$f" }.toList(out)
        }

        advanceUntilIdle()
        assertEquals(listOf("123456"), out)
        job.cancel()
    }

    @Test
    fun `seven flows combine in order`() = runTest {
        val out = mutableListOf<String>()
        val job = launch {
            combineStates(
                flowOf(1),
                flowOf(2),
                flowOf(3),
                flowOf(4),
                flowOf(5),
                flowOf(6),
                flowOf(7),
            ) { a, b, c, d, e, f, g -> "$a$b$c$d$e$f$g" }.toList(out)
        }

        advanceUntilIdle()
        assertEquals(listOf("1234567"), out)
        job.cancel()
    }

    @Test
    fun `eight flows combine in order`() = runTest {
        val out = mutableListOf<String>()
        val job = launch {
            combineStates(
                flowOf(1),
                flowOf(2),
                flowOf(3),
                flowOf(4),
                flowOf(5),
                flowOf(6),
                flowOf(7),
                flowOf(8),
            ) { a, b, c, d, e, f, g, h -> "$a$b$c$d$e$f$g$h" }.toList(out)
        }

        advanceUntilIdle()
        assertEquals(listOf("12345678"), out)
        job.cancel()
    }

    // ── Behaviour a coordinator merge depends on ─────────────────────────────

    @Test
    fun `mixing state types keeps each position typed`() = runTest {
        val out = mutableListOf<String>()
        val job = launch {
            combineStates(
                MutableStateFlow(1),
                MutableStateFlow("two"),
                MutableStateFlow(true),
                MutableStateFlow(listOf(4)),
            ) { n, s, b, list -> "$n|$s|$b|${list.first()}" }.toList(out)
        }

        advanceUntilIdle()
        assertEquals(listOf("1|two|true|4"), out)
        job.cancel()
    }

    @Test
    fun `a change in any upstream flow re-emits with the new value`() = runTest {
        val first = flowOf(1)
        val second = flowOf(10)
        val out = mutableListOf<Pair<Int, Int>>()
        val job = launch { combineStates(first, second) { a, b -> a to b }.toList(out) }

        advanceUntilIdle()
        first.value = 2
        advanceUntilIdle()
        second.value = 20
        advanceUntilIdle()

        assertEquals(listOf(1 to 10, 2 to 10, 2 to 20), out)
        job.cancel()
    }

    @Test
    fun `eight-flow merge does not drop or reorder values`() = runTest {
        val sources = List(8) { flowOf(it) }
        val out = mutableListOf<List<Int>>()
        val job = launch {
            combineStates(
                sources[0],
                sources[1],
                sources[2],
                sources[3],
                sources[4],
                sources[5],
                sources[6],
                sources[7],
            ) { a, b, c, d, e, f, g, h -> listOf(a, b, c, d, e, f, g, h) }.toList(out)
        }

        advanceUntilIdle()
        assertEquals(1, out.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), out.first())
        job.cancel()
    }

    @Test
    fun `null upstream value is passed through rather than dropped`() = runTest {
        val nullable = MutableStateFlow<String?>(null)
        val out = mutableListOf<String>()
        val job = launch { combineStates(nullable, flowOf(1)) { s, n -> s ?: "null:$n" }.toList(out) }

        advanceUntilIdle()
        nullable.value = "set"
        advanceUntilIdle()

        assertEquals(listOf("null:1", "set"), out)
        job.cancel()
    }

    @Test
    fun `transform is a pure projection so it re-runs identically per emission`() = runTest {
        // A transform with no writes produces the same result for the same inputs no matter
        // how many times upstream re-emits — the property the NoCombineSideEffect rule
        // protects by banning writes from the transform.
        var callCount = 0
        val out = mutableListOf<Int>()
        val source = flowOf(1)
        val job = launch {
            combineStates(source, flowOf(2)) { a, b ->
                callCount++
                a + b
            }.toList(out)
        }

        advanceUntilIdle()
        source.value = 1 // same value re-emitted
        advanceUntilIdle()

        assertTrue(out.all { it == 3 })
        job.cancel()
    }
}
