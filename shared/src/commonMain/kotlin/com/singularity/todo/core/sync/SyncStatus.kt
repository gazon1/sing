package com.singularity.todo.core.sync

/**
 * Sync status for entities.
 */
enum class SyncStatus(val value: String) {
    LOCAL_ONLY("LOCAL_ONLY"),
    PENDING_PUSH("PENDING_PUSH"),
    IN_SYNC("IN_SYNC"),
    PENDING_PULL("PENDING_PULL"),
    FAILED("FAILED");

    companion object {
        fun fromValue(value: String): SyncStatus = entries.firstOrNull { it.value == value } ?: LOCAL_ONLY
    }
}
