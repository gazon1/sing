<!-- Archived from PROGRESS.md on 2026-10-05. This epic is complete:
     implementation finished and verification closed out 2026-10-04,
     per the epic's own status line. Kept for history, not read as
     current state. -->

## Epic: navigation-policy-cc-hardening

**Start date:** 2026-10-03
**Status:** 🔄 in progress — implementation complete, verification closed out 2026-10-04
**Epic branch:** `feat/nav-cc-hardening`
**Issues:** [#28 navigation as policy](https://github.com/gazon1/singularity-clone-kmp/issues/28) ·
[#29 CC hardening](https://github.com/gazon1/singularity-clone-kmp/issues/29) (closed) ·
[#30 migration 31→32](https://github.com/gazon1/singularity-clone-kmp/issues/30) ·
[#27 desktop blank screen](https://github.com/gazon1/singularity-clone-kmp/issues/27) (fixed here)

---

### Phase A — Configuration Cache hardening ✅

ADR `2026-10-04-configuration-cache-hardening`. Issue #29 closed.

| Item | Status |
|---|---|
| A1 Baseline measurements (store/reuse/cold) | ✅ |
| A2 `:mcp-server:build` green under CC + fat-jar smoke | ✅ |
| A3 Stale-test-classes hypothesis | ✅ disproven — not reproducible |
| A4 CI: explicit `--configuration-cache`, store cache, report upload (`.github/workflows/ci.yml`) | ✅ |
| A5 `org.gradle.configuration-cache.problems=fail` | ✅ |

**Bugs this found (none were on the plan):** `distZip`/`distTar` duplicate entries from the same
Compose artifacts under `androidx.*` and `org.jetbrains.*` coordinates; `mcp-server`'s hand-rolled
`platformModule()` had drifted from the shared one, so the jar threw `NoDefinitionFoundException` on
startup; `Migration31To32` backfilled `target_id` *after* the generated migration dropped
`task_id`, permanently stranding every pre-v32 database; `TOOL_ANNOTATIONS` had 25 of 26 keys
dead in snake_case-vs-dotted drift.

### Phase B — Navigation as a policy ✅

ADR `2026-10-04-navigation-policy`. OpenSpec change `navigation-open-policy`. Issue #28.

| Item | Status |
|---|---|
| B0 Inventory (`docs/navigation.md`) + green baseline | ✅ |
| B1 `NavigationPolicy` + shell `Navigator.open/close` facade | ✅ |
| B2 Typed entity ids on route properties + `rememberNavBackStackTyped` | ✅ |
| B3 Exhaustive `familyOf` classification | ✅ |
| B4 Scoped cleanup (7 deprecated `AppDestination` members, legacy callbacks) | ✅ |
| B5 Full verification | ✅ |

**Intentional behaviour change:** cross-feature opens that used to fall through a per-origin
allow-list and degrade into "go back" now actually open (e.g. project → task from the Plans tab,
task → linked note). Covered by desktop flow tests.

### Phase C — Test-infrastructure defects found while closing out ✅

Not on the original plan; all found by actually running the verification B5 asked for.

| Item | Status |
|---|---|
| C1 Every test class tagged `fast`/`slow` (was 16 of 218) | ✅ |
| C2 `TestTagCoverageTest` — untagged class fails the build | ✅ |
| C3 `failOnNoDiscoveredTests` on `shared`/`desktopApp` test tasks | ✅ |
| C4 `RoomTaskRepositoryContractTest` — 14 failures on `":memory:"` | ✅ |
| C5 `TestTagsWiringTest` — 3 stale `knownUnapplied` entries | ✅ |
| C6 detekt ktlint `ClassSignature` in `TaskEditorCallbacks.kt` | ✅ |
| C7 `openspec validate --all --strict` fully green | ✅ |

#### Epic retro (2026-10-04)

**What went well:**
- Two platform entry files lost their duplicated `when(dest)` dispatch; the rule now lives in one
  pure function that is unit-testable without Compose.
- `familyOf` is exhaustive by construction, so adding a route without a family is a compile error.

**What didn't go well:**
- **The verification step was the only thing that ever ran the tests.** `.github/workflows/ci.yml`
  passes `-Ptest.tags=fast,slow`; JUnit matches tags per class, and only 16 of 218 classes
  carried one. `:desktopApp:test` executed **zero** tests and still reported `BUILD SUCCESSFUL`.
  Every
  navigation test and every desktop flow test had never run in CI. Green was structural, not real.
- B0's "green baseline" was recorded against a command that did not execute the suite. A baseline
  is only evidence if the number of executed tests is part of the record.
- Two OpenSpec items were left failing (`jvm-coroutine-diagnostics`, `nav3-desktop-jvm-entry-dispatch`)
  by the previous epic, so `--strict` was already red before this one started.
- An unplanned third workstream (undo-snackbar `Popup`, popup `testTags`, Maestro flows) surfaced
  during the Maestro smoke pass and now has its own ADR and commit.

**ADR-worthy findings:**
- Silent tag-filter skips → `TestTagCoverageTest` + `failOnNoDiscoveredTests`
- Undo snackbar needs its own window → `2026-10-04-undo-snackbar-and-popup-testtags`
- Issue #27 root cause (entry-local `remember` owning a shell-level back stack) →
  `2026-10-04-navigation-policy` + `deferred-backlog.md`

**Carry-over (out of scope, tracked):** dedup of `AndroidNavEntries`/`JvmNavEntries`
(ADR `2026-10-02-nav-entries-dedup-deferred`).
