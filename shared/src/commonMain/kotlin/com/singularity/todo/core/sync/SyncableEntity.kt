package com.singularity.todo.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * Marker interface for entities that support sync.
 * All syncable entities must implement this.
 */
interface SyncableEntity {
    /** String ID used by the sync protocol (e.g. TaskId.value). */
    val syncId: String
    val docType: DocType
    /** Server version from the last sync; 0 = not yet synced. */
    val syncServerVersion: Long
    /** Hybrid Logical Clock for causal ordering. Null for local-only entities. */
    val syncHlc: Hlc?

    /** Returns the JSON representation of this entity for sync. */
    fun toJson(): JsonObject
}
