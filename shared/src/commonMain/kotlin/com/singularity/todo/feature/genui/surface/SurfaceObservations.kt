package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.DataModel
import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/**
 * How a renderer watches a surface.
 *
 * These are the read side of [SurfaceController], kept out of it deliberately: the controller owns
 * the state and is the only thing allowed to change it, while everything here is a way of being
 * told about it. A renderer needs both, and only one of them may be written.
 *
 * Every observation is per identifier — one component, one path — rather than per surface. A
 * renderer subscribed to the whole surface re-renders when any part of it changes, and a tree of
 * containers multiplies that cost by the number of nodes.
 *
 * They are also extensions rather than members so that [SurfaceController] stays a class about
 * state: adding a fourth observation should not make the state holder larger.
 *
 * One component of one surface, emitting only when that component changes.
 *
 * Empty when the surface is absent, and it starts emitting if the surface appears — which is
 * what lets a renderer mount before the surface has been created.
 */
fun SurfaceController.component(surfaceId: SurfaceId, id: String): Flow<UiNode?> =
    surfaces.flatMapLatest { open: Map<SurfaceId, Surface> ->
        open[surfaceId]?.componentStore?.flow(id) ?: emptyFlow()
    }.distinctUntilChanged()

/** The root reference of a surface, which is not itself a component and has no flow of its own. */
fun SurfaceController.rootRef(surfaceId: SurfaceId): Flow<NodeRef?> =
    surfaces.map { open: Map<SurfaceId, Surface> -> open[surfaceId]?.rootId }.distinctUntilChanged()

/** The value at [path] in a surface's data model. */
fun SurfaceController.value(surfaceId: SurfaceId, path: UiPath): Flow<JsonElement?> =
    surfaces.flatMapLatest { open: Map<SurfaceId, Surface> ->
        val model: DataModel? = open[surfaceId]?.dataModel
        if (model == null) emptyFlow() else model.flow(path).distinctUntilChanged()
    }
