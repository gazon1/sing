package com.singularity.todo.core.sync

import java.security.MessageDigest

/**
 * Checksum utilities for sync shadow-state comparison.
 *
 * The active conflict strategy is remote-wins LWW in [SyncEngine]
 * (fast-reject via [checksum]); the former per-field `merge()` was
 * dead code and was removed — see `2026-09-25-repository-architecture-gaps`.
 */
object ConflictResolver {

    /**
     * Computes a SHA-256 checksum of the JSON state.
     * Used for shadow state comparison.
     */
    fun checksum(state: kotlinx.serialization.json.JsonObject): String {
        val normalized = state.entries.sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=$v" }
        return sha256(normalized.toByteArray())
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks if two checksums are equal.
     */
    fun checksumEquals(a: String, b: String): Boolean = a == b
}
