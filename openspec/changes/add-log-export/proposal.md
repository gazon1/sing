# add-log-export

## What

Add a log-export feature that bundles the rolling log files into a ZIP archive and offers it to the user via the platform's native share sheet.

## Why

Users cannot access app logs without developer tools (ADB, desktop file browser). Log export lets users share diagnostics with the team or save them for later analysis.

## How

- `FileSharePort` — new KMP port: `shareFile(path, mimeType)` → `Boolean`
- Android: `FileProvider` (existing `logs/` path already registered in `file_paths.xml`)
- JVM: `Desktop.browse` on the file URI
- `LogBundleExporter` — common class that reads `log.0`…`log.3`, builds ZIP via `BackupCodec`
- Settings screen wires a button that calls `LogBundleExporter.export()` then `FileSharePort.shareFile()`
- Exported archive name: `singularity-logs-<timestamp>.zip`

## Known limitations (documented in design.md, not fixed here)

- `RedactingLogWriter` does not redact `cause` chain or `tag` fields
- Android `beginShutdown()` is never called — log tail may be lost
- Release builds do not write `Info`-level to file

## References

- `docs/decisions/deferred-backlog.md` — `log-export-has-no-surface`
- `docs/decisions/deferred-backlog.md` — `log-writer-redaction-gaps`
- `core/files/FileSharePort.kt` — new port interface
- `core/log/LogBundleExporter.kt` — new exporter class
