---
title: "MR-1 Quick Wins — Post-MR-1 Findings"
date: 2026-09-30
status: open
tags: [mr-review, tech-debt]
---

## Context

MR-1 applied 11 mechanical quick-win fixes: Clock suppression directives, TimeConstants, IdGenerator singleton, TaskMutations error handling, System.err→kermit, `!!` removal, check-tags.sh fix, DIGEST trim. All checks pass (detekt 0, jvmTest 1223 green, ktlint clean).

## Items Fixed Immediately

- Smart cast failures on delegated properties in `LoginScreen` and `SyncConfigScreen`: replaced `errorMessage!!` inside `if (errorMessage != null)` with local captures (`val capturedError = errorMessage`). Same pattern for `state.lastSyncedAt`.
- `SavedAgendaViewModel.canSave`: `view != null` guard was removed — it was added in MR-1 to eliminate a `!!`, but it broke Create mode (which has `view = null` by design). Guard moved to Edit branch only.

## Items Requiring Future Refactor

| Severity | File | Issue | Estimated |
|---|---|---|---|
| **S-2** | `SyncConfigScreen.kt` | Unwired surface: screen has no call site (`find-unwired-surfaces.py` 1 finding). The sync config flow is vestigial. | MR-3 or MR-4 |
| **S-3** | `check-tags.sh` | LEGACY_RAW and ALLOW_PATTERNS arrays are documented but never checked in the validation loop. The script works correctly (it skips non-LEGACY tags), but the arrays are dead code. | MR-1 (quick) |
| **S-3** | `refresh-decisions-digest.py` | Per-tag cap of 15 items was needed to bring DIGEST from 2083 → 1488 lines. The cap is arbitrary — if new ADRs keep landing, the cap will need adjusting. | Ongoing maintenance |

## Items Deferred (Already Tracked)

- `SyncConfigScreen` unwired — tracked in `2026-09-29-sync-config-screen-has-no-host`
- Unwired UI actions (Phase 2) — deferred per plan
- i18n 174 sites — deferred per plan

## Open Questions

- LEGACY_RAW/ALLOW_PATTERNS dead code in check-tags.sh: should these arrays be removed entirely, or are they intended for future use? The script works correctly without them.
