---
title: Tech Debt Metrics — October 2026 Follow-up
date: 2026-10-02
description: Bulk metrics scan of remaining tech debt after MR-12a/3a/3b/3c
status: accepted
---

## Context

After completing MR-12a (FK constraint), MR-3a (NoteEditor decomposition), and MR-3b (detekt baseline), a bulk scan was run to track remaining debt and establish baseline metrics for future regression detection.

## Metrics Snapshot (2026-10-02)

| Metric | Count | Threshold | Status |
|--------|-------|-----------|--------|
| `= {}` no-op lambdas in commonMain | **240** | 100 | 🔴 Over threshold |
| `error("not implemented")` in commonMain | **28** | 0 | 🟡 Known-dead code (MR-6, not called by tests) |
| `@Suppress("FunctionSignature")` | **8** | 0 | 🟡 Same pattern, hidden debt |
| Detekt issues (shared) | **0** | 0 | ✅ Clean |
| Detekt issues (desktopApp) | **0** | 0 | ✅ Clean |
| androidApp detekt findings | **9** | 0 | 🟡 ignoreFailures=true; no baseline |

## `= {}` Distribution (top files)

| File | `= {}` count |
|------|-------------|
| NotesListScreen.kt | 24 |
| TaskEditorContent.kt | 22 |
| BackupScreen.kt | 18 |
| SavedAgendaScreen.kt | 17 |
| NotePreviewScreen.kt | 16 |
| AgendaContent.kt | 14 |
| SettingsScreen.kt | 14 |
| SimpleFilterSheet.kt | 12 |
| ProfileSwitcherScreen.kt | 11 |
| AiProviderSettingsScreen.kt | 11 |

39 files total have at least one `= {}`.

## Why not fixed now

- The 240 no-op lambdas are scattered across 39 files with no single root cause.
- Each requires case-by-case judgment (hoist handler, make nullable, or delete).
- Automated replacement would introduce behavioral changes without deep understanding.
- Follow-up MR recommended: bulk pass with `NoEmptyOnClickLambda` detekt rule
  (warn-only initially, promoted to error after cleanup).

## `error("not implemented")` — MR-6 verdict

28 occurrences in `InMemoryTaskDao`. Verified: zero are called by any test.
Not removed in this session because the removal is paired with deletion of the
unused method implementations, which changes the `TaskDao` contract surface.
MR-6 (scheduled) addresses this.

## androidApp detekt (9 findings, ignoreFailures=true)

No baseline exists. 9 findings are real but pre-existing.
`ignoreFailures = true` kept; documented in this ADR.
Fix: run `detektBaseline` + inline suppressions on new findings only.

## Action items

1. **No-op lambdas**: Author `NoEmptyOnClickLambda` detekt rule (warn-only).
   Target: reduce from 240 to <100 over 3 MR passes.
2. **InMemoryTaskDao dead methods**: MR-6 — remove unused implementations.
3. **androidApp baseline**: generate baseline, keep `ignoreFailures=true` until clean.
4. **FunctionSignature suppress**: audit each of 8 occurrences; most are multiline
   parameter lists in migrated files — fix or document.

## Links

- MR-3 from previous session: bulk no-op lambda cleanup
- MR-6: InMemoryTaskDao dead method removal
- MR-7: detekt baseline generation
