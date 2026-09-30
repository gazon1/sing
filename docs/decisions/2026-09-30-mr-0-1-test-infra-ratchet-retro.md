---
title: Retro MR-0 + MR-1: Test Infrastructure Ratchet
date: 2026-09-30
status: accepted
summary: MR-0+MR-1 retrospective — NoDate bisect findings, diagnostics infrastructure, 3 ADRs, 2 skills
---

# Retro MR-0 + MR-1: Test Infrastructure Ratchet

Date: 2026-09-30
Scope: MR-0 (NoDate bisect) + MR-1 (per-test diagnostics package)

---

## MR-0: NoDate Bisect — What We Found

**Goal:** Locate where undated tasks disappear — FakeAppDatabase, production repository, ViewModel, or UI.

**Finding:** The bug was in the UI layer — `LazyColumn` item keys were not unique
across agenda sections. Each section (NoDate, Today, Tomorrow, ThisWeek, etc.) used
`key = { it.task.id.value }`, but when the same task ID appeared in multiple sections
(e.g. a task that falls into both "Today" and "ThisWeek" buckets), Compose crashed with
`IllegalArgumentException: Key ... was already used` — a duplicate key collision across
sections, not within one section.

**Fix:** `key = { "${section.name}/${it.task.id.value}" }` — defensive depth key.
Also added `discard = true` on all overlapping bucket selectors in `AgendaPresets.Inbox`
and `AgendaPresets.Upcoming` so the same task cannot appear in two sections simultaneously.

**No FakeAppDatabase bug found.** Gate for MR-4: stays in sequence.

**Side discovery:** `Nav3State` was missing `rememberViewModelStoreNavEntryDecorator`.
Every tab was resolving `LocalViewModelStoreOwner.current` to the window owner and sharing
one ViewModelStore — tabs were seeing each other's state. Created ADR
`2026-09-30-nav3-need-viewmodelstore-decorator.md`.

---

## MR-1: Per-Test Diagnostics Package

### What was built

**`RingBufferLogWriter`** — lock-free `CopyOnWriteArrayList`-based ring buffer, 2000-entry
capacity, implementing Kermit `LogWriter`. Provided as a Koin `single` so each test gets its
own buffer. Retrievable by type in `FailureBundle.capture()`.

**`TestKermitModule`** — `single { RingBufferLogWriter(capacity = 2_000) }` added to the
Koin test graph.

**`FailureBundle.capture()`** — writes to `build/diagnostics/<TestClass>/attempt-<N>/`:
- `screenshot.png` via `captureToImage()` (no-arg, available in desktop 1.12.0)
- `db-state.txt` via `FakeAppDatabase.dumpAll()`
- `kermit.log` via `ringBuffer.drain()`
- Semantics tree capture was **removed** — `toTree()` and `onRoot()` are not in the
  desktop Compose 1.12.0 test API

**`awaitTag` explainer** — when a tag is not found, enumerates all available tags via
`TAG_PATTERN = Regex("""testTag=[^\s,\]]+""")` on `SemanticsNode.toString()` and includes
them in the `AssertionError` message.

**Harness catch restructuring** — the original nested `try/catch` in `runDesktopAppTest`
had an unreachable `catch` block (the test's outer `runCatching` intercepted the re-throw
before the harness's inner `catch` fired). Simplified so the harness re-throws directly
and the positive-control test verifies only the explainer message text.

### Unexpected: API asymmetry between Android and Desktop

`toTree()` and `onRoot()` exist in the Android Compose test API but are absent from the
desktop 1.12.0 JAR. `captureToImage()` (no-arg) IS available on desktop.
`screenshot.png` is the only visual artifact available; semantics tree dumps are not.

### Unexpected: `Logger.setLogWriters()` is process-global

`setLogWriters()` replaces the global writer list. Calling `initTestLogging()` (which
writes `PlainStdoutWriter`) after `installRingBuffer()` would overwrite the ring buffer.
Fixed by maintaining a module-level `MutableList<LogWriter>` that both functions append to
before calling `setLogWriters(*kermitWriters.toTypedArray())`.

### Unexpected: Nav3 ViewModelStore decorator

Without `rememberViewModelStoreNavEntryDecorator`, every tab resolves the window-level
`ViewModelStoreOwner` and shares one store. Any multi-tab usage of `koinViewModel {
parametersOf(def) }` produces cross-tab state leakage. Decorator added to `Nav3State.kt`.

---

## Sweep results

```bash
git grep -nE 'setLogWriters|startKoin|System\.setProperty|Dispatchers\.setMain' \
  -- '*.kt' ':!*Test.kt' ':!test/'
# 0 violations in test source sets
```

Clock/SystemDate: no violations in test files.

---

## What changed in ADRs and skills

**New ADRs:**
- `2026-09-30-nav3-need-viewmodelstore-decorator.md` — root cause + fix
- `2026-09-30-agenda-section-discard-missing.md` — duplicate key crash + fix
- `2026-09-30-desktop-test-diagnostics.md` — ring buffer via DI, FailureBundle,
  awaitTag explainer, API limitations

**New skills:**
- `singularity-todo-nav3-decorators/` — ViewModelStore vs SaveableStateHolder
- `singularity-todo-agenda-section-design/` — bucket/discard semantics, overlap table

**Updated skills:**
- `singularity-todo-desktop-compose-ui-tests` — failure diagnostics documentation

---

## Critical bugs fixed (committed as `fix(scope):`)

| Bug | Fix |
|---|---|
| LazyColumn duplicate key crash across agenda sections | Defensive key `${section.name}/${id}` + `discard=true` on overlapping buckets |
| Tab state cross-contamination (no ViewModelStore decorator) | Added `rememberViewModelStoreNavEntryDecorator` to `Nav3State` |
| `Logger.setLogWriters()` overwriting ring buffer | Shared `MutableList<LogWriter>` in `TestLogging.kt` |
| `toTree()`/`onRoot()` not in desktop 1.12.0 | Removed tree capture; screenshot only |

## Non-critical (in ADRs, not blocking)

- Desktop Compose test API is a subset of Android — diagnostics must adapt
- `AgendaPresets.Inbox` bucket overlap — `discard` required on narrower buckets
- Harness catch re-throw flow — nested `try/catch` had unreachable branch

---

## Status at close of MR-1

- `check.sh` — all 4/4 green
- `git status` in worktree — clean
- Main checkout — dirty (unrelated `FileLogWriter` changes, not ours)
- Test count: `:desktopApp:test` 12 tests, `:shared:jvmTest` — all green
