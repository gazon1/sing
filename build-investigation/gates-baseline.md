# Stage 4 — gate baseline (after the split, before Stage 5)

Date: 2026-11-05. Tree: `62bc2e012` (`refactor(core): extract :core-database from :shared`)
plus the Stage 4 gate and CI wiring.

This records the state the gates are in before the performance experiment, so Stage 5 can
separate "the split made this worse" from "this was already red".

## Architecture gates (`:shared:archTest`)

| Run | Result |
|---|---|
| fast (default population) | green — 50 classes, 235 tests |
| slow (`-Ptest.tags=slow`) | `ArchitectureTest` 5 findings, `TestTagsWiringTest` 6 unapplied constants |

The 5 `ArchitectureTest` findings are the pre-existing debt recorded in
`build-investigation/baseline.md`:

- the domain layer imports `data`/`presentation` in `AgendaUiState.kt`,
  `ProjectDetailUiState.kt`, `TaskDetailUiState.kt` (3);
- `SyncOutboxDao.claim()` / `release()` have no ownership predicate (2).

`TestTagsWiringTest` names `MENU_CALENDAR`, `Settings.REMINDER_DEFAULTS_SECTION`,
`AGENT_TOOL_CALL_CARD`, `AGENT_TOOL_CALL_STATUS`, `AGENT_TOOL_CALL_SUMMARY`,
`AGENT_TOOL_CALL_SUMMARY_TOGGLE` as declared but never applied.

Stage 4 extended the source scans to `:core-domain` and `:core-database`
(`SourceScan.productionRoots`, `CommonMainKonsistScope.roots`, `VacuumIsForbiddenTest`, and the
entity/DAO scans). The counts above are the same as the base commit's: the move added no
finding, and no gate now passes over zero files — the scans assert non-empty roots and
entities.

## Detekt

| Task | Result |
|---|---|
| `:core-domain:detekt` | green (`baseline-shared.xml`; the baseline is keyed by file name, so the moved findings stay suppressed) |
| `:core-database:detekt` | green (same baseline) |
| `:shared:detekt` | 4 distinct unbaselined findings: `LongMethod` in `TaskCheckbox.kt`, `NoUnstableCollectionParam` in `LocalizedDateFormatter.kt` and `TaskEditorDueDateRow.kt`, `SwallowedException` in `ChatScreen.kt` |

The 4 are pre-existing and outside every file the cut changed. `build-investigation/baseline.md`
records 15 detekt findings at the base commit; the Stage 4 tree reports 4 distinct for
`:shared`, so the cut did not grow it, and the new modules carry no unbaselined finding.

## Gate scripts

| Check | Result |
|---|---|
| `check-room-schema-integrity.py` | green: `SCHEMA_VERSION 53`, 50 exports, continuous chain |
| its unit tests / impact-map tests | 52 / 48 pass |
| `changed-modules.py` | 48 tests pass; knows `:core-domain` and `:core-database`, their reach and checks |
| `check-test-runs.py` | 56 tests pass; `core-domain:jvmTest` floor 5, `shared:archTest` 235, `shared:jvmTest` 4306 |
| workflow exit-status / env-context gates | green |

## Doc gates

Not run in Stage 4, and no document content was changed by it. `build-investigation/baseline.md`
records them as red at the base commit; that state is unchanged here, not masked.
