# Backup chain to production

## Request

BackupExporter filters deltas by its own cursor (not sync_state.lastLsn); restoreFromCloud applies records (currently open fails on chain).

Additional concerns raised: chain length bound + memory in sealChain; head.count allocation before authentication; UnsupportedArchiveVersion message; golden tests pin only v1; EncryptedArchive refactor; BackupCodec.export ByteSink/ByteSource; split BackupError into two types; readExactly reuse or remove.

## What the code actually does

- `BackupExporter.export` (shared/.../core/backup/BackupExporter.kt:40) reads all entities for the user and seals a full snapshot via `codec.export`. No cursor, no delta filtering.
- `BackupRepository.restoreFromCloud` (shared/.../core/backup/BackupRepository.kt:204) downloads the remote (encrypted) archive to a temp file, then calls `importer.import` (zip-based). The importer's `codec.importFromSource` reads a zip. The remote download path uses `archive.open(source, target, password)` (RemoteBackupServiceImpl.kt ~140), which calls `openChain` with a callback that throws `BackupError.BrokenChain` when entries exist. Thus a chained archive cannot be restored.
- `EncryptedArchive.sealChain` (shared/.../core/backup/EncryptedArchive.kt:~120) seals entries into memory (`sealedEntries = entries.mapIndexed { ... SealedEntry(...) }`) before writing. No bound on total entry count or total bytes (only per-entry MAX_ENTRY_BYTES).
- `EncryptedArchive.openChain` reads `container = readContainer(source)` (including head.count) before `crypto.deriveKey`. The loop allocates `ByteArray(length)` per entry before password verification. Count bound added (MAX_CHAIN_ENTRIES=1024) in this session.
- `BackupError.UnsupportedArchiveVersion` (shared/.../core/backup/BackupError.kt:50) message: "The archive's version is $version, and this build reads versions ${readableVersions.sorted().joinToString(" and ")}".
- `BackupCodec.export` signature (shared/.../core/backup/BackupCodec.kt:28): takes `destPath: String, fs: FileSystem`, not ByteSink/ByteSource.
- `BackupError` is a single sealed class with all subclasses.
- `readExactly` does not exist; `readOrNull` is used (EncryptedArchive.kt, private).
- Golden tests: `ArchiveCryptoGoldenJvmTest` / `ArchiveCryptoGoldenAndroidTest` pin a v1 archive only.

## Reproduction

Not reproduced — this is an investigation of unimplemented features, not a bug. The symptom ("open fails on chain") is confirmed by reading RemoteBackupServiceImpl.download which calls archive.open (not openChain with a real handler).

## Verified facts

- `EncryptedArchive.open` refuses chained archives: it calls `openChain` with onEntry throwing `BrokenChain` (EncryptedArchive.kt, open function).
- `RemoteBackupServiceImpl.download` calls `archive.open(source, target, password)` (RemoteBackupServiceImpl.kt ~140), so a chained remote archive fails to download.
- `sealChain` buffers all entries in memory before writing the head (EncryptedArchive.kt, sealChain).
- `openChain` reads head.count before key derivation (EncryptedArchive.kt, openChain).
- `BackupExporter` has no cursor concept (BackupExporter.kt, entire file).
- `BackupCodec` interface takes fs + destPath, not ByteSink/ByteSource (BackupCodec.kt).
- `BackupError` is one sealed class (BackupError.kt).
- `readExactly` absent; `readOrNull` present (EncryptedArchive.kt).

## Hypotheses

- H1: Applying chain entries in restoreFromCloud requires calling `openChain` with an onEntry that upserts each delta into the DB. Confirm by: implementing and testing. Refute by: showing openChain's onEntry can apply deltas without re-sealing.
- H2: A chain length bound of 1024 entries is sufficient for incremental backups. Confirm by: measuring typical delta counts. Refute by: observing chains exceeding 1024 in production.

## Open questions

- What is the cursor schema? Is it per-user or per-table?
- How does restoreFromCloud distinguish a chained archive from a v1 snapshot?
- Should golden tests include a v2 (chained) archive?

## Constraints found

- Koin DI: ports are interfaces, implementations platform-specific.
- Room schema rules: migrations must be registered.
- Detekt: NoUnstableCollectionParam, NoCollectionMaterializationInComposable.
- Test convention: fakes, not mocks; @Tag("slow") for slow tests.

## References

- shared/src/commonMain/kotlin/com/singularity/todo/core/backup/EncryptedArchive.kt
- shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupExporter.kt
- shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupImporter.kt
- shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupRepository.kt
- shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupCodec.kt
- shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupError.kt
- shared/src/commonMain/kotlin/com/singularity/todo/core/backup/RemoteBackupService.kt
EOF
echo written