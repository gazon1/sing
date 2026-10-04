# Tasks — jvm-coroutine-diagnostics

**Status:** completed

All tasks are verification checklists — the implementation was verified during development.

---

## Implementation verification

### Module: desktopApp

- [x] **Agent** — `kotlinx-coroutines-debug` attached via `jvmArgs("-javaagent:...")` with eager String path (CC-safe)
- [x] **Configuration** — `coroutinesDebugAgent` named configuration, `testImplementation` dependency
- [x] **CoroutineDiagnostics.kt** — `dump(testClass, attempt)` returns String, guards `!DebugProbes.isInstalled`
- [x] **FailureBundle 4th artifact** — `coroutinesFile` lazy accessor, independent `runCatching` block, capture order db→kermit→dump→screenshot
- [x] **Retry artifact staleness** — `outputDir.deleteRecursively()` before writing, clean directory per attempt
- [x] **CoroutineDiagnosticsTest** — 4 smoke tests (suspended coroutine, real dispatcher, job hierarchy, infinite flow)
- [x] **FailureBundle KDoc** — corrected to reflect mkdirs-only (not delete prior content)

### Module: shared

- [x] **Agent** — `coroutinesDebugAgent` configuration + `jvmArgs("-javaagent:...")` with eager String path (CC-safe)
- [x] **shared.build.dir system property** — passed from Gradle, read lazily in extension
- [x] **CoroutineDiagnostics.kt** — `dump(testClass)` returns String (no attempt param in shared)
- [x] **CoroutineDiagnosticsTest** — 2 smoke tests (suspended coroutine, infinite flow)
- [x] **FailureContextExtension** — `writeCoroutineDump()` before early-return guard; writes on ANY failure, not only AssertionError
- [x] **META-INF/services** — `FailureContextExtension` registered (was orphaned from `7985c234`)
- [x] **CoroutinesTimeoutExtension** — catches timeout exceptions, writes `coroutines-timeout.txt`
- [x] **ExtensionServiceRegistrationTest** — architecture test verifying all registered extensions are loadable

### CI

- [x] **desktopApp artifact** — `desktopApp/build/diagnostics/**` already covered by existing upload
- [x] **shared artifact** — `shared/build/diagnostics/**` added as separate upload step
- [x] **OpenSpec CI gate** — fixed hardcoded `/home/max/.nvm` path, removed `|| true`

### Documentation

- [x] **ADR** — `2026-10-03-kotlinx-coroutines-debug.md`, status: accepted
- [x] **debugging-investigation skill** — Step 5: coroutines.txt first, manual DebugProbes not needed
- [x] **desktop-compose-ui-tests skill** — FailureBundle table: 4 artifacts, capture order, hang-proof rationale
- [x] **AGENTS.md** — new "Coroutine test failures" block with investigation order

---

## Follow-up (not implemented)

- [ ] **Global `@Timeout` configuration** — `CoroutinesTimeoutExtension` catches timeout exceptions but does not generate them. Set `junit.jupiter.timeout.default` globally in `shared/build.gradle.kts` to make the extension fire.
- [ ] **build-logic convention plugin** — extract `jvmArgs("-javaagent:...")` pattern into a reusable convention plugin once `includeBuild("build-logic/convention")` is wired (currently blocked by README criteria).

---

## Verification command

```bash
./gradlew :shared:jvmTest :desktopApp:test --no-daemon
```
