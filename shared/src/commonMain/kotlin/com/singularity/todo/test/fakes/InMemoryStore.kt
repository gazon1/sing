package com.singularity.todo.test.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal in-memory CRUD store for Fake repositories.
 *
 * **Composition, not inheritance.** Each Fake repository owns an [InMemoryStore] instance
 * and adds its own domain-specific methods (e.g., `toggleComplete`, `watchSubtasks`).
 *
 * Why not a base class or mixin:
 * - Effective Kotlin (Rask): "Avoid simple boundary classes" — a generic abstract class
 *   that only shares state is a anti-pattern.
 * - Each repository has different change-notification patterns
 *   (`SharedFlow<Task>` vs `StateFlow<List<Project>>`).
 * - Domain methods (e.g., `toggleComplete`, `watchSubtasks`) stay in the concrete class,
 *   not in a generic base.
 *
 * @param keyOf Function to extract the string key from an entity.
 * @param initial Initial map state.
 */
class InMemoryStore<E : Any>(
    private val keyOf: (E) -> String,
    initial: Map<String, E> = emptyMap(),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Map<String, E>> = _state.asStateFlow()

    /** Returns the entity with [id], or null if not found. */
    operator fun get(id: String): E? = _state.value[id]

    /** Returns all entities as a list. */
    fun values(): List<E> = _state.value.values.toList()

    /** Returns true if an entity with [id] exists. */
    fun contains(id: String): Boolean = _state.value.containsKey(id)

    /** Upserts (insert or replace) [entity]. */
    fun upsert(entity: E) {
        _state.value += (keyOf(entity) to entity)
    }

    /** Removes the entity with [id]. */
    fun remove(id: String) {
        _state.value -= id
    }

    /** Clears all entities. */
    fun clear() {
        _state.value = emptyMap()
    }

    /**
     * Seeds [items] by merging into existing state.
     * Existing entries with the same id are replaced.
     */
    fun seed(items: Collection<E>) {
        _state.value += items.associateBy(keyOf)
    }

    /**
     * Seeds [items] by merging into existing state.
     * Convenience overload for vararg.
     */
    fun seed(vararg items: E) = seed(items.toList())

    /** Returns a snapshot copy of the current state. */
    fun snapshot(): Map<String, E> = _state.value.toMap()
}
