---
name: singularity-todo-backup
description: Backup/restore pattern for the Singularity Todo KMP app. Use whenever adding backup, restore, export, import, or zip compression features. Handles BackupExporter/BackupImporter orchestration, DSL builders with @DslMarker, sealed BackupError, the registered schema-migration chain, the container's own versions and sealing (ArchiveFormat, ArchiveSealer), and BackupCodec platform implementations.
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
- `core/backup/ArchiveSealer.kt` — the key a push seals under: a password, or a device key
- `core/backup/EncryptedArchive.kt` — the container's shape: the three versions' bytes, the seal and the open
- `core/backup/DeviceKeyStore.kt` — the key a scheduled run seals with, and the block that wraps it under the password
- `core/backup/BackupCursor.kt` — the chain's bookkeeping: the base and the changes that follow it
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

## The Container Layer

The payload is what an archive *carries* — the DTOs, the manifest, the JSON. The
**container** is the envelope around it: magic, version, salt, nonce, and the
sealed bytes. Most of this skill is about the payload; this section is about the
envelope, and it has its own version number, its own key and its own failure
family (`ArchiveEnvelopeError`).

### The three container versions

`ArchiveFormat` holds them. A version is a **meaning**, not a format revision:
it is never reused, and a build keeps reading every version it shipped with —
so the readable set is a *set*, and a reader refuses anything outside it by
name.

| Version | Meaning | Written today by |
|---|---|---|
| 1 | the full snapshot: one sealed stream, nothing after the header | nothing — read only |
| 2 | a chain: a base and the sealed deltas that follow it | a **manual push** (`ArchiveSealer.UnderPassword`) |
| 3 | a chain sealed under a device key, with that key wrapped under the password | a **scheduled push** (`ArchiveSealer.UnderDeviceKey`) |

`ArchiveFormat.READABLE_VERSIONS` is the set this build reads (`1, 2, 3`);
`ArchiveFormat.LATEST_VERSION` is only the newest version the build knows how to
write (`3`) — and it is **not** the version of every write. The version follows
*how* an archive is sealed, not which build writes it: a person pushing by hand
gets a version-2 container, a scheduled run gets a version-3 one.

### Two version numbers, not one

The payload's schema version and the container's version are different numbers
with different rules. They meet in the same archive, which is exactly why
confusing them writes a broken change.

| | The container's version | The payload's schema version |
|---|---|---|
| Lives in | `ArchiveFormat` | `BackupFormat.SCHEMA_VERSION` |
| Today | versions 1–3 | `7` |
| A number means | a meaning of the envelope | the shape of the payload's JSON |
| The rule for a change | a new meaning: a **new** number, never reused, and the reader's set grows | bump it **and** register the migration step |
| A reader that cannot cope says | `ArchiveEnvelopeError.UnsupportedArchiveVersion` | `BackupPayloadError.UnsupportedSchemaVersion` / `SchemaTooOld` |
| What carries the number forward | a new reader: every build keeps the versions it shipped with | `BackupMigrations` — the registered chain (see the section below) |

So: change the **bytes of the envelope** — a new field in the header, a
different key in the container, a new framing — and you bump the *container*
version. Change the **rows inside** — a new field on a task, a new entity — and
you bump the *schema* version and add the step. Bump only one and the archive
breaks: a container version without a reader that supports it refuses the
archive outright, and a schema version without a step fails the migration
loudly rather than quietly restoring defaults.

There is a **third** number in the same archive, and it is worth naming so it
is not confused with the two above: `BackupFormat.FORMAT_VERSION` — the
version of the *zip layout* — travels in the manifest, and a manifest that
carries a newer one is refused
(`BackupPayloadError.UnsupportedFormatVersion`). So an archive carries three
versions: the container's version in the envelope's header, and the format
version and the schema version in the manifest. The one a change to the rows
bumps is the schema version; the format version moves when the archive's
*layout* moves — a new entry in the zip, a new directory — which is a third
kind of change again.

### What sealing does, and where the key lives

Sealing happens **on the device**, before anything leaves it. What lands in the
bucket is ciphertext: the server never sees the key, and never sees the rows.
`EncryptedArchive` is the shape of it:

- **magic** (`SGBK`) says "this is an encrypted archive" — an archive from
  before the encryption is refused with a sentence that names the problem
  instead of failing as a checksum mismatch;
- **salt** feeds the key derivation, so two archives under one password do not
  share a key; **nonce** is the GCM nonce of the base and is fresh every time;
- the **header** (magic, version, salt, nonce, and — for a chain — the head and
  the key block) is the cipher's *authenticated data*: nothing in it can be
  changed without the archive ceasing to open;
- the **changes come first and the base last**, because the base is the part
  that is streamed and a stream cannot say its own sealed length before it is
  sealed; the head (the entry count and their total length) sits inside the
  header, which is what makes a **truncated chain** detectable.

What sealing does **not** cover is the name of the object: a bucket listing
shows the file name, and always will.

The key is `ArchiveSealer`, and it is a sealed interface with exactly two
cases — the difference between a manual push (`push(backupId, password)`) and a
scheduled one (`pushScheduled()`) is this value and nothing else:

| | `UnderPassword` | `UnderDeviceKey` |
|---|---|---|
| The key | the person's password | a random 256-bit device key, wrapped under the password |
| Where it lives | nowhere — it is typed, and it is not stored | the platform keystore (`DeviceKeyStore`), never derived from the password, never sent anywhere |
| Who holds it | the person, for the length of the call | the device, for the length of the call |
| What a restore needs | the password | the password too — it unwraps the key out of the container |
| The container it writes | version 2 | version 3 |

The secret is **consumed**: the call that takes it wipes it before it returns
(`password.fill('\u0000')` / `deviceKey.fill(0)`), so a failure does not leave a
password lying in memory for the next attempt, and a person who has to try
again types it again.

### The chain: a base and the changes that follow it

A chain is **a base plus the deltas that follow it**, and the two cannot be
separated: a change says "on top of that base", so a push that carried only
the changes would carry nothing that could be restored.

- The **bookkeeping** is `BackupCursor`: the base the changes were written
  against (`baseArchivePath`) and the changes in order, each with the archive
  it belongs to. It is device-local and never uploaded.
- A **push** assembles the chain — `BackupRepositoryImpl.chainFor()` finds the
  base and the changes — and seals the lot as one container. The object in the
  bucket keeps the name of the archive the person pushed; the bytes are the
  base and its changes.
- An **import** travels the same way: `ImportOptions` carries the base
  (`sourcePath`) and the changes that follow it (`entries`, each a whole
  delta). Today the changes reach an import on one path only — the restore
  from the cloud, which hands over the deltas it downloaded along with the
  base. A local import takes the base alone: the DSL builder has no field for
  the changes, and the repository's local import does not collect them from
  the chain the export wrote beside the archive.
- Each change is **applied whole** — a delta is a set of rows, not a patch —
  through the same path the base goes through. `BackupImporter` decodes and
  migrates *every* change before it writes anything, so a corrupt or
  schema-incompatible change fails the import before a single row is touched;
  then the base and the changes go in, in order, in one transaction, and the
  result counts the **merged** state rather than the base's.
- A chain is **bounded**: `ArchiveFormat.MAX_CHAIN_ENTRIES` (32 changes) and
  `ArchiveFormat.MAX_CHAIN_BYTES` (16 MB). The export starts a fresh base
  when the chain passes the bound, and a push whose chain is over the bound is
  sealed again **without** the changes — the base alone is a whole backup, and
  a whole backup that reaches the bucket beats a failure over bookkeeping.

### The edge cases

- **A chain whose base is missing.** A push sends the snapshot alone: when the
  base or one of the changes is not on the device, `chainFor()` gives up on the
  chain and seals the archive by itself. It is not what the person asked for,
  but it is a backup. An import has no such fallback — the base is what is
  being restored, so a base that is not there fails the import on the read
  itself: the codec cannot open the file, and the import says so.
- **A chain whose base is corrupt.** The failure names the layer: a container
  that is not an archive, a wrong password or an unreadable version is
  `ArchiveEnvelopeError` (`NotEncrypted`, `WrongPassword`,
  `UnsupportedArchiveVersion`, `Unusable`), and a manifest or checksum that
  does not hold is `BackupPayloadError` (`MalformedManifest`,
  `ChecksumMismatch`). A **broken chain** — an entry out of order, a chain
  shorter than its head says, bytes after the last entry — is
  `ArchiveEnvelopeError.BrokenChain`: the archive refuses to pretend it read
  one, rather than applying the entries before the break and calling the
  result restored. Nothing is written when this happens (validation precedes
  the transaction), and an archive downloaded from the cloud that fails to
  import is deleted again rather than left in the person's list of backups.
- **A device that is not authorized to seal.** `pushScheduled()` asks the
  `DeviceKeyStore` for its wrapped key first, and there is none on a device
  that was never authorized: it fails with
  `BackupStorageError.DeviceNotAuthorized`. The reasoning is the feature's own
  — an archive sealed under a key nobody can unwrap is not a backup, so the
  run refuses rather than pushing something nobody can read. The person
  authorizes the device once, with `authorizeScheduledBackup(password)`, which
  wraps the key under the password, wipes the password, and seals the newest
  local archive again and pushes it once more. It cannot reach into the cloud
  and change the copy that is already there: that copy keeps asking for the
  password it was made with, and the app says so.

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
| v1 → v2 | tasks gain `parentTaskId`, `estimateMinutes`, `recurrenceRule`, `outgoingLinks`, `aiSuppressedTagIds`; notes gain `isPinned`, `pinnedAt`, `color`, `sortOrder`, `wordCount`, `charCount`, `outgoingLinks`, `taskId` (`parentTaskId` is among the fields the step writes, although today's `TaskDto` no longer carries it) |
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

DTOs strip sync metadata on `toDto()`. Sync fields restored as defaults on `toEntity()`:
```kotlin
fun TaskEntity.toDto(): TaskDto = TaskDto(
    id, title, description, priority, kind, projectId, dueDate, dueTime,
    startDate, startTime, endDate, endTime, accentColor, emoji, completedAt,
    someday, archivedAt, isPinned, estimateMinutes, recurrenceRule, seriesId,
    workflowState, outgoingLinks, aiSuppressedTagIds, createdAt, updatedAt
    // serverVersion, syncStatus, hlc — NOT included (defaults on restore)
)
```

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
