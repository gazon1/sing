---
title: "StableJson: centralized Kotlinx Serialization Json config"
date: 2026-09-18
status: accepted
tags: [serialization, architecture, core, kotlinx-serialization]
---

## Context

Before this decision, identical `Json { ... }` configs were copied in:
- `SyncEngine.kt`
- `BackupImporter.kt`
- `BackupExporter.kt`

Each had minor variations. Adding a new config option required updating all three.

## Decision

Single shared `StableJson: Json` in `core/serialization/StableJson.kt`:

```kotlin
val StableJson: Json = Json {
    classDiscriminator = "_type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}
```

All serialization of persistent state (sync patches, backup payloads, drafts, caches) uses this config.

## Why these settings

| Setting | Value | Reason |
|---|---|---|
| `classDiscriminator` | `"_type"` | Polymorphic sealed hierarchies (e.g. `BackupManifest`) encode their variant as `"_type": "Task"` |
| `encodeDefaults` | `true` | All fields serialized even at default values — simplifies diffing and debugging; cost is negligible |
| `ignoreUnknownKeys` | `true` | Schema evolution: old backups may contain fields that no longer exist in current code |

## Consequences

- Adding a new config option is a one-line change
- `StableJson` replaces 3+ local copies
- `encodeDefaults = true` increases JSON size marginally — acceptable for backup/sync payloads
- `ignoreUnknownKeys = true` silently drops unknown fields on deserialization — intentional for graceful migration

## Links

- `core/serialization/StableJson.kt` — the single definition
