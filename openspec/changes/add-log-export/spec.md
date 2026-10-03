# Log Export — Observable Behavior

**capability:** `log-export` | **status:** proposed

---

## REQ-LE-001

The system SHALL provide a `FileSharePort` that accepts a file path and MIME type and offers the file to the platform's native share sheet.

**Scenario: Android share**
- User taps "Export Logs"
- App creates archive at `filesDir/logs/singularity-logs-<timestamp>.zip`
- App calls `FileSharePort.shareFile(archivePath, "application/zip")`
- Android share sheet opens with the ZIP as `EXTRA_STREAM`
- User picks an app (email, cloud drive, etc.) to receive the file

**Scenario: Desktop share**
- User taps "Export Logs"
- App creates archive at `~/.singularity-todo/logs/singularity-logs-<timestamp>.zip`
- App calls `FileSharePort.shareFile(archivePath, "application/zip")`
- Desktop opens system file browser at the archive location

---

## REQ-LE-002

The export archive SHALL contain all existing rolling log files (`log.0.txt` … `log.3.txt`) that exist at the time of export.

- Files are included oldest-to-newest
- The archive is a ZIP using the same structure as `BackupCodec`: `MANIFEST.json` + `payload.json` + `attachments/<name>`
- Each log file is stored as `attachments/log.<N>.txt`

---

## REQ-LE-003

The export operation SHALL be triggered from the Settings screen.

- A single "Export Logs" button triggers the export
- While the archive is being built, the UI shows a loading indicator
- After the share sheet opens, control returns to the app

---

## REQ-LE-004

If no log files exist at the time of export, the system SHALL create an empty archive containing only the manifest and a payload with `"logs":[]`.

---

## REQ-LE-005

The system SHALL NOT delete log files as part of the export operation.

---

## REQ-LE-006

The `FileSharePort` binding MUST appear in both `PlatformModule.android.kt` and `PlatformModule.jvm.kt`.

- `single<FileSharePort> { AndroidFileSharePort(get()) }` on Android
- `single<FileSharePort> { JvmFileSharePort() }` on JVM

---

## REQ-LE-007

The `LogBundleExporter` class MUST NOT call `FileLogWriter` directly — it reads log files as data, not as a log writer.

---

## Known limitations

### Redaction gaps

`RedactingLogWriter` does not currently redact `cause` chains or `tag` fields. Exported logs may contain sensitive data in these fields. This is not fixed in this change.

### Android shutdown flush

`FileLogWriter.beginShutdown()` is never called on Android (`Application.onTerminate` never fires). Log entries written after the last implicit flush (triggered by the OS on process termination) are not included in the export. This is not fixed in this change.

### Release build log level

In release builds, `Info`-level log entries are not written to the file (global severity filter). Exporting a release build produces warn/error/fatal entries only. This is not fixed in this change.
