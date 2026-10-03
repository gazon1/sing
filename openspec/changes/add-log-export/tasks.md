# Tasks — add-log-export

**status:** proposed

---

## Phase 6 (OpenSpec change artifacts)

- [x] `proposal.md` — created
- [x] `spec.md` — created
- [x] `design.md` — created
- [ ] `tasks.md` — this file

## Implementation (Phase 7)

### Module: shared

- [ ] Create `core/files/FileSharePort.kt` — interface `shareFile(path, mimeType): Boolean`
- [ ] Create `shared/androidMain/.../AndroidFileSharePort.kt` — `FileProvider` + `Intent.ACTION_SEND`
- [ ] Create `shared/jvmMain/.../JvmFileSharePort.kt` — `Desktop.browse`
- [ ] Add `single<FileSharePort> { AndroidFileSharePort(get()) }` to `PlatformModule.android.kt`
- [ ] Add `single<FileSharePort> { JvmFileSharePort() }` to `PlatformModule.jvm.kt`
- [ ] Create `core/log/LogBundleExporter.kt` — reads `log.*.txt`, calls `BackupCodec.export()`
- [ ] Add `FakeFileSharePort` to `test/fakes/FakeRepositories.kt`
- [ ] Add `FileSharePort` row to AGENTS.md port table
- [ ] Add `LogBundleExporter` to Koin module (factory, not single — stateless)

### Module: shared (tests)

- [ ] Unit test `LogBundleExporterTest` — empty dir, single file, multiple files
- [ ] Unit test `AndroidFileSharePortTest` — file exists / does not exist / URI error
- [ ] Unit test `JvmFileSharePortTest` — file exists / does not exist

### Module: androidApp

- [ ] Add "Export Logs" button to Settings screen
- [ ] Wire button: call `LogBundleExporter.export()` then `FileSharePort.shareFile()`
- [ ] Show loading indicator during export
- [ ] Show error snackbar if export fails

### Module: desktopApp

- [ ] Add "Export Logs" button to Settings screen (same composable, desktop variant)
- [ ] Wire same logic as Android

### CI / verification

- [ ] `KoinGraphValidationTest` passes with new bindings (JVM mirrors android bindings)
- [ ] `PlatformParityTest` passes with new port
- [ ] `./gradlew :shared:jvmTest` green
- [ ] `./gradlew :shared:compileTestKotlinJvm` green

### ADR (known limitations, deferred)

- [ ] ADR: `RedactingLogWriter` redaction gaps — cause chain and tag fields not redacted
- [ ] ADR: `beginShutdown` not called on Android — log tail may be lost
