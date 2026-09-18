---
title: "Backup format: zip + MANIFEST + payload + SHA-256 checksum"
date: 2026-09-18
status: accepted
tags: [backup, architecture, format, core]
---

## Context

Backups need to be portable, versioned, and verifiable. The app supports export (local zip), import (restore from zip), push (upload to cloud), and pull (download from cloud).

## Decision

### Zip structure

```
backup_<profileId>_<timestamp>.zip
├── MANIFEST.json     — version metadata + payload checksum
├── payload.json      — all entities (tasks, notes, projects, tags, settings)
└── attachments/      — binary files, named by attachment ID
```

`BackupFormat` constants:

| Constant | Value | Meaning |
|---|---|---|
| `FORMAT_VERSION` | `1` | Zip layout version — bump if zip structure changes |
| `SCHEMA_VERSION` | `1` | Entity shape version — bump if entity fields change |
| `ENTRY_MANIFEST` | `"manifest.json"` | Manifest entry name |
| `ENTRY_PAYLOAD` | `"payload.json"` | Payload entry name |
| `DIR_ATTACHMENTS` | `"attachments/"` | Attachments directory prefix |

### Manifest schema

```json
{
  "formatVersion": 1,
  "schemaVersion": 1,
  "profileId": "abc",
  "createdAt": "2026-09-18T10:00:00Z",
  "payloadChecksum": "sha256_hex_of_payload_bytes"
}
```

Checksum validation on import:
1. `payloadChecksum` verified against actual SHA-256 of `payload.json` bytes
2. `formatVersion` must be ≤ `BackupFormat.FORMAT_VERSION` — else throw `UnsupportedFormatVersion`
3. `schemaVersion` must be ≤ `BackupFormat.SCHEMA_VERSION` — else throw `UnsupportedSchemaVersion`

### Payload schema

```json
{
  "tasks": [...],
  "notes": [...],
  "projects": [...],
  "tags": [...],
  "settings": {...}
}
```

Each entity type is serialized with `StableJson`. Unknown keys are ignored (`ignoreUnknownKeys = true`).

### Attachments

Binary files stored at `attachments/<attachmentId>`. Original filenames are **not** preserved in the zip — only the attachment ID is used as the filename.

## Consequences

- **Positive**: Single portable file with integrity check (SHA-256)
- **Positive**: Version fields allow future migrations (FORMAT_VERSION / SCHEMA_VERSION)
- **Positive**: `ignoreUnknownKeys` provides graceful forward compatibility
- **Negative**: Attachments are not deduplicated across backups — two backups with the same file will contain two copies
- **Negative**: No incremental backup — every export is a full snapshot

## Links

- `core/backup/BackupFormat.kt` — version constants
- `core/backup/BackupCodec.kt` — zip encode/decode port
- `core/backup/BackupDomain.kt` — manifest structure and validation
- `core/backup/BackupExporter.kt` / `BackupImporter.kt` — use-case side
