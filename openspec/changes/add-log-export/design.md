# Log Export — Architecture and Risk

**change:** `add-log-export`

## Design decisions

### Why `LogBundleExporter` reads files, not the writer

`initLogging` is called **before** `startKoin` (`SingularityApp.kt:64-69`). At that point there is no Koin scope, so the `LogBundleExporter` cannot be injected into `FileLogWriter` as a dependency. Making the writer depend on the exporter would require injecting the exporter into `initLogging`, which would need to flow through `Application` — an unnecessary coupling.

The exporter therefore reads files directly from disk, treating them as opaque data.

### Why a ZIP archive and not raw files

`BackupCodec` already provides a working ZIP implementation (`JvmBackupCodec` / `AndroidBackupCodec`). Reusing it keeps the archive format consistent with backups and avoids duplicating zip-creation code. The `LogBundleExporter` is a thin shell over `BackupCodec.export`.

### Why `FileSharePort` is a new port and not `SharePort`

`SharePort.shareText()` takes text content and opens a text-sharing chooser. Sharing a binary ZIP archive requires a different Android intent (`ACTION_SEND` with `EXTRA_STREAM` vs `ACTION_SEND` with `EXTRA_TEXT`) and a different Desktop API (`Desktop.browse` vs clipboard). A separate port keeps the two operations semantically distinct.

### Android `FileProvider` authority

The existing `FileProvider` registered in `AndroidManifest.xml` uses authority `${applicationId}.fileprovider`. The `logs/` path is already mapped in `res/xml/file_paths.xml`. `AndroidFileSharePort` uses the same authority, so no manifest changes are needed.

## Module ownership

| Component | Module |
|---|---|
| `FileSharePort` interface | `shared/core/files/` |
| `AndroidFileSharePort` | `shared/androidMain/core/files/` |
| `JvmFileSharePort` | `shared/jvmMain/core/files/` |
| `LogBundleExporter` | `shared/core/log/` |
| Settings screen | `androidApp` / `desktopApp` |

## Dependencies introduced

```
shared/core/log/LogBundleExporter.kt
  → BackupCodec (existing)
  → FileSystem (existing)
  → FileLogWriter.DEFAULT_FILE_COUNT (companion object, constant)

shared/core/files/FileSharePort.kt  (new interface)
  → no dependencies

shared/androidMain/.../AndroidFileSharePort.kt
  → Context (from Koin)

shared/jvmMain/.../JvmFileSharePort.kt
  → java.awt.Desktop (standard library)
```

## Risk: log directory path stability

`FileLogWriter` accepts a `Path` (okio `Path`). `LogBundleExporter` accepts a `String` for the directory path to stay within the `FileSystem` port interface (which uses `String`). This means the path is converted from `Path` to `String` at the call site. If `FileLogWriter`'s internal path representation changes, the call site must be updated.

Risk level: low. `Path` is consistently used as `String` throughout the codebase's file APIs.

## Risk: `beginShutdown` never called on Android

`FileLogWriter.beginShutdown()` drains the write buffer within 2 seconds. On Android, `Application.onTerminate()` is never called, so the buffer is never drained explicitly. The OS flushes on process death, but the timing is not guaranteed. Log entries written between the last write and process termination may not be in `log.0.txt` when exported.

This is documented as a known gap and is not fixed in this change. A proper fix would require moving logging initialization to after Koin starts, which has its own complications (logs from the Koin initialization phase would be lost).
