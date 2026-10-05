package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.DataModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The components of one surface, held so that a change to one of them is observable on its own.
 *
 * This is the difference between a surface that re-renders and one that re-composes. When the
 * components were an immutable map inside an immutable [Surface], a single `updateComponents`
 * replaced the map, and every node in the tree — each of which observed the whole map — was
 * invalidated. Adding a component to the catalog therefore multiplied the cost of every update,
 * which is the practical reason the catalog stayed small.
 */
class ComponentStore(initial: Map<String, UiNode> = emptyMap()) {

    private val state: MutableStateFlow<Map<String, UiNode>> = MutableStateFlow(initial)

    /** The whole component map. Collected only where the root itself is being resolved. */
    val all: StateFlow<Map<String, UiNode>> = state.asStateFlow()

    fun snapshot(): Map<String, UiNode> = state.value

    /** Adds or replaces components, leaving the rest untouched. */
    fun put(components: Map<String, UiNode>) {
        if (components.isEmpty()) return
        state.value = state.value + components
    }

    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        state.value = state.value - ids
    }

    /**
     * One component's value, emitting only when that component changes.
     *
     * `distinctUntilChanged` is what makes this worth having: without it, a map rebuild that left
     * this component identical would still wake every collector, which is the cost this class
     * exists to remove.
     */
    fun flow(id: String): Flow<UiNode?> = state.map { it[id] }.distinctUntilChanged()
}

/**
 * A GenUI surface: a tree of [UiNode] components backed by a [DataModel].
 *
 * A class rather than a data class on purpose. It is no longer a value: its components change in
 * place through [componentStore], and copying it on every update was the mechanism that forced the
 * whole tree to re-render.
 *
 * @param id unique identifier for this surface
 * @param rootId the id of the root node in [componentStore]
 * @param dataModel the reactive data store for bound form fields and state
 */
class Surface(
    val id: SurfaceId,
    val rootId: NodeRef,
    components: Map<String, UiNode> = emptyMap(),
    val dataModel: DataModel = DataModel(),
) {
    val componentStore: ComponentStore = ComponentStore(components)

    /** A snapshot of every component. Prefer [componentStore] where reactivity is needed. */
    val components: Map<String, UiNode> get() = componentStore.snapshot()
}

/** Unique identifier for a [Surface]. */
@JvmInline
value class SurfaceId(val value: String)
