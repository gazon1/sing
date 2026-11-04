---
name: singularity-todo-backup
description: Backup/restore pattern for the Singularity Todo KMP app. Use whenever adding backup, restore, export, import, or zip compression features. Handles BackupExporter/BackupImporter orchestration, DSL builders with @DslMarker, sealed BackupError, the registered schema-migration chain, and BackupCodec platform implementations.
---

# singularity-todo-backup

Documents the backup/restore architecture for the Singularity Todo KMP Compose app (Android + JVM Desktop).

## Core Architecture

```
BackupExporter: DAOs → DTOs → JSON payload → manifest → BackupCodec.export()
BackupImporter:  BackupCodec.import() → validate → migrate → DAOs upsert
```

**Key files** (all under `shared/src/commonMain/kotlin/com/singularity/todo/`):
- `core/backup/BackupFormat.kt` — constants (FORMAT_VERSION=1, SCHEMA_VERSION=7, MIN_SUPPORTED_SCHEMA_VERSION=1, entry names)
- `core/backup/BackupManifest.kt` — `@Serializable`; compatibility is checked by `BackupDomain.validateManifest()`, not by the manifest itself
- `core/backup/BackupPayload.kt` — `@Serializable` wrapper with schemaVersion, all DTO lists, and `removals` (the rows an archive says are gone)
- `core/backup/BackupTaskDtos.kt` — the task rows and the rows that hang off a task; the other domains have a file each (`core/backup/BackupNoteDtos.kt`, `core/backup/BackupProjectDtos.kt`, `core/backup/BackupTagDtos.kt`, `core/backup/BackupAttachmentDtos.kt`, `core/backup/BackupSavedViewDtos.kt`, `core/backup/BackupProfileDtos.kt`). **Sync metadata stripped on toDto()**
- `core/backup/BackupDomain.kt` — pure: `sha256Hex()`, `extractUserIdHash()`, `buildManifest()`, `validateManifest()`
- `core/backup/BackupMigrations.kt` — `Map<Int, (JsonObject) → JsonObject>` chain via `fold()`
- `core/backup/BackupError.kt` — sealed root of the failures; catching it catches every family
- `core/backup/ArchiveEnvelopeError.kt` — the container: NotEncrypted, WrongPassword, UnsupportedArchiveVersion, Unusable, BrokenChain, MissingPassword, ChainTooLong
- `core/backup/BackupPayloadError.kt` — the payload: UnsupportedFormatVersion, UnsupportedSchemaVersion, SchemaTooOld, ChecksumMismatch, MalformedManifest
- `core/backup/BackupStorageError.kt` — the device's own files: FileNotFound, DeviceNotAuthorized
- `core/backup/BackupCodec.kt` — interface with `export()` / `import()`, `CodecReadResult` data class
- `core/backup/BackupOptions.kt` — `@DslMarker` + `ExportOptionsBuilder` / `ImportOptionsBuilder` with nullable `var userId: UserId?` (value classes can't be lateinit)
- `core/backup/BackupRepository.kt` — interface, `backups: Flow<List<BackupMetadata>>` polls every 5s
- `core/backup/BackupExporter.kt` — orchestrator: DAOs → DTOs → JSON → manifest → codec.export()
- `core/backup/BackupImporter.kt` — orchestrator: codec.import() → validate → migrate → DAOs upsert
- `core/backup/RemoteBackupService.kt` — port + `RemoteBackupServiceImpl` (Supabase Storage; the archive is sealed on the device, so the server sees ciphertext)
- `core/backup/ArchiveFormat.kt` — the **container's** versions (1 snapshot, 2 chain, 3 device-keyed chain): a versioning of its own, separate from `BackupFormat.SCHEMA_VERSION`
- `feature/backup/BackupViewModel.kt` — MVI (`MviViewModel` + `BackupIntent`), takes the current user from `AuthRepository`
- `feature/backup/BackupScreen.kt` — Scaffold with TopAppBar, buttons, LazyColumn of BackupListItem, SnackbarHost

**Platform-specific** (jvmMain / androidMain):
- `core/backup/JvmBackupCodec.kt` — `java.util.zip.ZipOutputStream` / `ZipInputStream`
- `core/backup/AndroidBackupCodec.kt` — `java.util.zip` (same API, minSdk 24)

## DSL Builders Pattern

```kotlin
// Usage:
repository.export(exportOptions {
    userId = currentUserId
    destPath = "backup-2026-10-05.zip"
    includeAttachments = true
})

// Builder definition (BackupOptions.kt):
@DslMarker annotation class BackupDsl

@BackupDsl class ExportOptionsBuilder {
    var userId: UserId? = null  // nullable — value class can't be lateinit
    var ownerId: UserId? = null
    var destPath: String? = null
    var includeAttachments: Boolean = true
    var appVersion: String = appVersion().name
    fun build() = ExportOptions(userId, ownerId, ownerId, destPath ?: error("destPath required"), includeAttachments, appVersion)
}

fun exportOptions(block: ExportOptionsBuilder.() -> Unit): ExportOptions =
    ExportOptionsBuilder().apply(block).build()
```

## Where an export lands

`destPath` takes one of two kinds of value, and the shape of the value says
which:

- a **file name** (`backup.zip`) — the repository joins it to the backup
  directory it owns, so the archive lands beside the app's other backups;
- a **destination the person chose** — an absolute path, or a `content://` URI
  from the system save dialog — used verbatim, with nothing joined to it.

A value carrying a scheme (`://`), a `content:` prefix, or a path separator is
the second kind; a value carrying none of them is the first.

What differs downstream: a `content://` document is written straight into (no
sibling to write next to it, no rename to make), while an absolute path is a
file and gets the temporary-sibling-then-move treatment every file gets. The
change that follows the snapshot — the chain — is written only for a
destination inside the backup directory, and an archive saved elsewhere is not
one of the backups `observeAll` lists: that list is the backup directory.

## Failure Families

The failures are three families under one sealed root, `BackupError`:

| Family | What it is about |
|---|---|
| `ArchiveEnvelopeError` | the container: the bytes, the version, the password, the chain |
| `BackupPayloadError` | what the container carried: the manifest, the schema, the checksum |
| `BackupStorageError` | the device's own files: a backup that is not there |

```kotlin
import com.singularity.todo.core.backup.ArchiveEnvelopeError
import com.singularity.todo.core.backup.BackupPayloadError
import com.singularity.todo.core.backup.BackupStorageError

// In UI (exhaustive over the backup's three families):
when (val result = repository.import(opts)) {
    is Result.success -> showSuccess()
    is Result.failure -> when (val err = result.exceptionOrNull()) {
        is ArchiveEnvelopeError -> showContainerError(err)  // the container: password, version, chain
        is BackupPayloadError -> showPayloadError(err)      // what it carried: manifest, schema, checksum
        is BackupStorageError -> showStorageError(err)      // the device's own files
        else -> showUnexpectedError(err)
    }
}
```

Catching the root catches every family: `catch (error: BackupError)` is what
most callers want, and what the remote backup service does. A caller that has to
tell a container failure from a payload failure says which family it means, as
above — and the case inside the family says what happened.

## Migration Chain

`BackupMigrations` is a **registered** chain: every version from 1 up to `CURRENT - 1` has a step, and a version that has no step is an **error**, not a no-op.

```kotlin
// BackupMigrations.kt:
object BackupMigrations {
    const val CURRENT = BackupFormat.SCHEMA_VERSION  // 7 today

    // Map<fromVersion, transform> — the key is the version the step comes FROM
    private val migrations: Map<Int, (JsonObject) -> JsonObject> =
        mapOf(1 to v1ToV2, 2 to v2ToV3, 3 to v3ToV4, 4 to v4ToV5, 5 to v5ToV6, 6 to v6ToV7)

    fun migrate(payload: JsonObject, from: Int, to: Int = CURRENT): JsonObject {
        require(from <= to) { "from ($from) > to ($to)" }
        return (from until to).fold(payload) { acc, v ->
            val step = migrations[v] ?: error("no migration from schema v$v")
            step(acc)
        }
    }
}
```

The fold used to skip a version it had no transform for, so a payload written at a version the chain did not cover walked over the gap and came out as the payload it went in as — with whatever the DTO defaults happened to be. The symptom was "my data is wrong", not "this backup is too old". A missing step now fails loudly.

### The steps, and what each one writes

| Step | What it writes |
|---|---|
| v1 → v2 | tasks gain `parentTaskId` (`null` on every task — a v1 payload never carried the link, so a migrated backup's tasks come back top-level), `estimateMinutes`, `recurrenceRule`, `outgoingLinks`, `aiSuppressedTagIds`; notes gain `isPinned`, `pinnedAt`, `color`, `sortOrder`, `wordCount`, `charCount`, `outgoingLinks`, `taskId` |
| v2 → v3 | `agendaViews` (an empty list when the payload has none) |
| v3 → v4 | nothing but `schemaVersion` — the 8 new entity types default to empty |
| v4 → v5 | `verb` on every entry of `taskDependencies` (`BLOCKS`) |
| v5 → v6 | `workflowState` on every task (`Open`) |
| v6 → v7 | nothing but `schemaVersion` — `removals` is optional and defaults to empty |

Every step also writes the `schemaVersion` it lands on, so the payload that comes out of a migration is the payload this build writes.

### Adding a step

1. Write the transform `vNToVN+1` and have it write **every** field the new version added — a step that leaves a field to the DTO default hands the reader the default instead of the meaning.
2. Register it in `migrations` under the version it comes **from** (`migrations[7] = v7ToV8`).
3. Bump `BackupFormat.SCHEMA_VERSION` and add the new fields to the payload DTO.
4. Add the step to the table above — that table is the list of what each step writes, and a step that is not in it is a step nobody knows about.

## DTO vs Entity

DTOs strip sync metadata on `toDto()`, and the sync fields come back as defaults on
`toEntity()`. Apart from those, a row type carries **every** column its entity has —
the type is the list, and this page does not keep a second copy of it. `parentTaskId`
was the field that went missing that way, and a restore gave back a flat list of
tasks: a column added to an entity belongs on its DTO in the same change.

## java.util.zip (NOT Kompress)

Both JVM and Android use `java.util.zip` — stable, well-tested, available since API 1 on Android and standard on JVM. The archive is written through `fs.openWrite`, not through a buffer: an account with attachments is larger than a phone's memory. Attachments travel as **paths**, and each one is copied into the entry in chunks, so neither the archive nor an attachment is ever whole in memory.

```kotlin
// Export:
val sink = fs.openWrite(destPath)
try {
    ZipOutputStream(SinkOutputStream(sink)).use { zos ->
        zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
        zos.write(manifestBytes); zos.closeEntry()
        zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_PAYLOAD))
        zos.write(payloadBytes); zos.closeEntry()
        for ((name, sourcePath) in attachments) {
            zos.putNextEntry(ZipEntry(BackupFormat.DIR_ATTACHMENTS + name))
            copyInto(zos, fs.openRead(sourcePath))
            zos.closeEntry()
        }
    }
} finally {
    sink.close()
}

// Import:
ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
    var entry: ZipEntry? = zis.nextEntry
    while (entry != null) {
        val data = zis.readBytes()
        when (entry.name) {
            BackupFormat.ENTRY_MANIFEST -> manifest = data
            BackupFormat.ENTRY_PAYLOAD -> payload = data
            else -> if (entry.name.startsWith(BackupFormat.DIR_ATTACHMENTS))
                attachments[entry.name.removePrefix(BackupFormat.DIR_ATTACHMENTS)] = data
        }
        entry = zis.nextEntry
    }
}
```

## DI Wiring

`BackupCodec` is a **platform port**: each platform module binds its own implementation, and nothing constructs one inside DI.

```kotlin
// shared/src/androidMain/.../core/di/PlatformModule.android.kt
single<BackupCodec> { AndroidBackupCodec() }

// shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt
single<BackupCodec> { JvmBackupCodec() }
```

## MapFileSystem for Mock-Free Tests

`MapFileSystem` lives in `test/fakes/` — the same home as the other test doubles.

```kotlin
val fs = MapFileSystem()
val codec = JvmBackupCodec()
// write → read → verify identical bytes
```

## BackupRepository Interface

```kotlin
interface BackupRepository {
    fun observeAll(): Flow<List<BackupMetadata>>  // rescanned every 5s
    suspend fun createBackup(): Result<BackupResult>
    suspend fun export(options: ExportOptions): Result<BackupResult>
    suspend fun import(sourcePath: String): Result<RestoreResult>
    suspend fun delete(backupId: BackupId): Result<Unit>
    suspend fun push(backupId: BackupId, password: CharArray): Result<String>
    suspend fun listRemote(): Result<List<RemoteBackup>>
    suspend fun restoreFromCloud(remoteRef: String, password: CharArray): Result<RestoreResult>
    suspend fun authorizeScheduledBackup(password: CharArray): Result<ScheduledAuthorization>
    suspend fun isScheduledBackupAuthorized(): Boolean
    suspend fun pushScheduled(): Result<String>
}
```

There is **no `pull`**: a download that is not imported is a file nobody asked
for, and the repository owns the backup directory — so `restoreFromCloud` does
both steps, and a caller cannot aim a download anywhere. An *export* is the
other way round: it may be aimed at a destination the person chose.

`push` and `restoreFromCloud` **consume** the password: the array is wiped
before they return, so a failure does not leave the secret lying in memory for
the next attempt.

## Dual Versioning

- `FORMAT_VERSION` — zip container format (currently 1)
- `SCHEMA_VERSION` — payload schema (currently 7); `MIN_SUPPORTED_SCHEMA_VERSION` is 1, and a payload below it is rejected with `BackupPayloadError.SchemaTooOld`
- The **remote** container has versions of its own (`ArchiveFormat`): 1 is a full snapshot, 2 is a chain, 3 is a chain sealed under a device key
- `validateManifest()` checks: formatVersion ≤ current, schemaVersion ≤ current, schemaVersion ≥ MIN_SUPPORTED_SCHEMA_VERSION, payload checksum
