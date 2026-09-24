package com.singularity.todo.test.helpers

import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Asserts that [this] [StateFlow]'s value is of type [T] and optionally passes [predicate].
 * Uses [kotlin.test.assertIs] under the hood.
 *
 * Example:
 * ```
 * state.assertIs<Loading>()
 * state.assertIs<Loaded> { it.items.isNotEmpty() }
 * ```
 */
inline fun <reified T> StateFlow<*>.assertIs(noinline predicate: (T) -> Boolean = { true }): T {
    val current = value
    assertIs<T>(current)
    assertTrue(predicate(current), "State $current does not satisfy predicate")
    @Suppress("UNCHECKED_CAST")
    return current
}
