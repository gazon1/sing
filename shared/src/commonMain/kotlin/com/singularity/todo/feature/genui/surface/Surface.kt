package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.DataModel

/**
 * A GenUI surface: a tree of [UiNode] components backed by a [DataModel].
 *
 * @param id unique identifier for this surface
 * @param rootId the id of the root node in [components]
 * @param components all nodes in the surface, keyed by [NodeRef.id]
 * @param dataModel the reactive data store for bound form fields and state
 */
data class Surface(
    val id: SurfaceId,
    val rootId: NodeRef,
    val components: Map<String, UiNode>,
    val dataModel: DataModel,
)

/** Unique identifier for a [Surface]. */
@JvmInline
value class SurfaceId(val value: String)
