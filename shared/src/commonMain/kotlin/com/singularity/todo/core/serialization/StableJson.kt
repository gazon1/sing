package com.singularity.todo.core.serialization

import kotlinx.serialization.json.Json

/**
 * Shared JSON config for persistent state (drafts, caches, future serialization needs).
 *
 * Uses `classDiscriminator = "_type"` for polymorphic sealed hierarchies.
 * `encodeDefaults = true` ensures all fields are serialized even when at default values.
 * `ignoreUnknownKeys = true` tolerates schema evolution gracefully.
 *
 * Replaces 3 identical copies in: [SyncEngine], [BackupImporter], [BackupExporter].
 */
val StableJson: Json = Json {
    classDiscriminator = "_type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}
