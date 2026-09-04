package com.singularity.todo.core.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/**
 * Pure conflict resolution logic for sync.
 * Implements Last-Write-Wins (LWW) per field.
 */
object ConflictResolver {

    /**
     * Merges two JSON objects using HLC timestamps.
     * For each field, keeps the value with the newer HLC.
     * If HLCs are equal, keeps local (tiebreaker).
     *
     * @param local Local version
     * @param remote Remote version
     * @param localHlc HLC of local change (null if unknown)
     * @param remoteHlc HLC of remote change
     */
    fun merge(
        local: JsonElement,
        remote: JsonElement,
        localHlc: Hlc?,
        remoteHlc: Hlc?
    ): JsonElement {
        if (local is JsonNull) return remote
        if (remote is JsonNull) return local
        if (local !is kotlinx.serialization.json.JsonObject || remote !is kotlinx.serialization.json.JsonObject) {
            // Fallback: remote wins for non-objects
            return remote
        }

        return buildJsonObject {
            val allKeys = (local.keys + remote.keys).toSet()
            for (key in allKeys) {
                val l = local[key]
                val r = remote[key]
                put(key, when {
                    r == null -> l ?: JsonNull
                    l == null -> r
                    remoteHlc != null && localHlc != null && remoteHlc > localHlc -> r
                    else -> l
                })
            }
        }
    }

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
