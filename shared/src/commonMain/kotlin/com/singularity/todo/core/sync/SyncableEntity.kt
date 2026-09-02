package com.singularity.todo.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Marker interface for entities that support sync.
 * All syncable entities must implement this.
 */
interface SyncableEntity {
    val id: String
    val docType: DocType
    val serverVersion: Long
    val hlc: Hlc?

    /** Returns the JSON representation of this entity for sync. */
    fun toJson(): JsonObject
}

/**
 * Marker interface for repositories that emit change events for sync.
 */
interface SyncableRepository {
    val changes: Flow<out SyncableEntity>
}
