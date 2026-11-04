package com.singularity.todo.feature.genui.surface

import com.singularity.todo.core.text.Revisioned
import com.singularity.todo.feature.genui.schema.DataModel
import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.json.JsonElement

/**
 * The value at [path] in a surface's data model.
 *
 * How a renderer watches a surface: these are the read side of [SurfaceController], kept out of
 * it deliberately — the controller owns the state and is the only thing allowed to change it,
 * while everything here is a way of being told about it. A renderer needs both, and only one of
 * them may be written.
 *
 * Every observation is per path rather than per surface. A renderer subscribed to the whole
 * surface re-renders when any part of it changes, and a tree of containers multiplies that cost
 * by the number of nodes.
 *
 * They are also extensions rather than members so that [SurfaceController] stays a class about
 * state: adding a fourth observation should not make the state holder larger.
 *
 * The components of a surface are not observed here. A renderer reads them from the surface's
 * component store and derives what it draws from the map, so a flow per component would only be
 * a second way of saying the same thing.
 */
fun SurfaceController.value(
    surfaceId: SurfaceId,
    path: UiPath,
): Flow<JsonElement?> =
    surfaces.flatMapLatest { open: Map<SurfaceId, Surface> ->
        val model: DataModel? = open[surfaceId]?.dataModel
        if (model == null) emptyFlow() else model.flow(path).distinctUntilChanged()
    }

/**
 * The value at [path] in a surface's data model, and the count of the times the server
 * replaced it — read together.
 *
 * The count travels with the value because it belongs to the value: it says "this value
 * was rewritten from outside", and it is the count of *this path's* rewrites. A caller
 * that read the value and the count separately could pair them wrongly.
 */
fun SurfaceController.valueAt(
    surfaceId: SurfaceId,
    path: UiPath,
): Revisioned<JsonElement?> = snapshot(surfaceId)?.dataModel?.valueAt(path) ?: Revisioned.initial<JsonElement?>(null)

/**
 * The value at [path] in a surface's data model and the count of the server's rewrites
 * of it, as a stream.
 *
 * One emission carries both.
 */
fun SurfaceController.valueAtFlow(
    surfaceId: SurfaceId,
    path: UiPath,
): Flow<Revisioned<JsonElement?>> =
    surfaces.flatMapLatest { open: Map<SurfaceId, Surface> ->
        val model: DataModel? = open[surfaceId]?.dataModel
        if (model == null) emptyFlow() else model.flowAt(path).distinctUntilChanged()
    }
