# Log Export — Observable Behavior

**capability:** `log-export` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-LE-001

The system SHALL provide a `FileSharePort` that accepts a file path and MIME type and offers the file to the platform's native share sheet.

#### Scenario: Android share
- User taps "Export Logs"
- App creates archive at `filesDir/logs/singularity-logs-<timestamp>.zip`
- App calls `FileSharePort.shareFile(archivePath, "application/zip")`
- Android share sheet opens with the ZIP as `EXTRA_STREAM`
- User picks an app (email, cloud drive, etc.) to receive the file

#### Scenario: Desktop share
- User taps "Export Logs"
- App creates archive at `~/.singularity-todo/logs/singularity-logs-<timestamp>.zip`
- App calls `FileSharePort.shareFile(archivePath, "application/zip")`
- Desktop opens system file browser at the archive location

---

### Requirement: REQ-LE-002

The export archive SHALL contain all existing rolling log files (`log.0.txt` … `log.3.txt`) that exist at the time of export.

- Files are included oldest-to-newest
- The archive is a ZIP using the same structure as `BackupCodec`: `MANIFEST.json` + `payload.json` + `attachments/<name>`
- Each log file is stored as `attachments/log.<N>.txt`

#### Scenario: Export with four log files
- Log directory contains `log.0.txt` through `log.3.txt`
- User triggers export
- Archive contains `attachments/log.0.txt` … `attachments/log.3.txt`

---

### Requirement: REQ-LE-003

The export operation SHALL be triggered from the Settings screen.

- A single "Export Logs" button triggers the export
- While the archive is being built, the UI shows a loading indicator
- After the share sheet opens, control returns to the app

#### Scenario: Export triggered from Settings
- User is on the Settings screen
- User taps "Export Logs"
- Loading indicator appears
- Share sheet opens

---

### Requirement: REQ-LE-004

If no log files exist at the time of export, the system SHALL create an empty archive containing only the manifest and a payload with `"logs":[]`.

#### Scenario: Export with no log files
- Log directory is empty
- User triggers export
- Archive is created with empty `attachments/` directory

---

### Requirement: REQ-LE-005

The system SHALL NOT delete log files as part of the export operation.

#### Scenario: Log files persist after export
- Log directory contains `log.0.txt` … `log.3.txt`
- User triggers export
- Export completes
- Log files are still present in the log directory

---

### Requirement: REQ-LE-006

The `FileSharePort` binding MUST appear in both `PlatformModule.android.kt` and `PlatformModule.jvm.kt`.

- `single<FileSharePort> { AndroidFileSharePort(get()) }` on Android
- `single<FileSharePort> { JvmFileSharePort() }` on JVM

#### Scenario: Port binding inspection
- Both `PlatformModule.android.kt` and `PlatformModule.jvm.kt` are examined
- Each contains a `single<FileSharePort>` binding

---

### Requirement: REQ-LE-007

The `LogBundleExporter` class MUST NOT call `FileLogWriter` directly — it reads log files as data, not as a log writer.

#### Scenario: LogBundleExporter code inspection
- `LogBundleExporter.kt` source is examined
- No references to `FileLogWriter` or `LogWriter` appear in the file

---

## Known Limitations

### Redaction gaps

`RedactingLogWriter` does not currently redact `cause` chains or `tag` fields. Exported logs may contain sensitive data in these fields. This is not fixed in this change.

### Android shutdown flush

`FileLogWriter.beginShutdown()` is never called on Android (`Application.onTerminate` never fires). Log entries written after the last implicit flush (triggered by the OS on process termination) are not included in the export. This is not fixed in this change.

### Release build log level

In release builds, `Info`-level log entries are not written to the file (global severity filter). Exporting a release build produces warn/error/fatal entries only. This is not fixed in this change.
