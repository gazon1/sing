---
title: "Test helper architecture — observations and small fixes from the Ultron spike"
date: 2026-09-30
tags: [testing, desktop, architecture, technical-debt]
status: accepted
---

## Context

After merging the Ultron-ideas spike (`refactor/ultron-ideas-helpers`), several
concrete code issues and architectural observations were found before or during
merge. This ADR records the decisions made and the rationale for each.

---

## 1. `@OptIn` placement: class-level vs file-level

### Finding

16 of 18 desktop test files use `@OptIn(ExperimentalTestApi::class)` at
**class level** (on the class declaration line). Only 2 files use
`@file:OptIn` at the top of the file (`NotesScreenTest.kt`,
`TagsRenameUiTest.kt`). `ContextMenuTest` previously had 4 separate
`@OptIn` annotations on individual test methods.

### Decision

Both class-level and file-level `@OptIn` are correct. Use whichever matches
the project's convention in the file you're editing. When adding a new test
file, prefer `@file:OptIn` — it survives future additions of experimental API
calls without annotator drift.

**Applied fix:** `ContextMenuTest.kt` — 4 method-level `@OptIn` → single
`@file:OptIn` at top of file.

---

## 2. Unnecessary safe-call cast in `CreateTaskFlowTest`

### Finding

```kotlin
// Before (redundant cast):
val message = (thrown as? Throwable)?.message ?: ""

// After (correct):
val message = thrown?.message ?: ""
```

`exceptionOrNull()` already returns `Throwable?`; the safe-call cast to the same
type is a no-op that triggers a compiler warning.

### Decision

Fix applied directly. No ADR needed for a one-line warning fix.

---

## 3. `hasAnyAncestor(...)` — evaluated, not used

### Finding

The Ultron spike plan proposed adding `awaitTag(matcher)` overload to support
matchers like `hasAnyAncestor(...)`. The overload was implemented
(`DesktopNavigation.kt:204`), and the docstring mentions `hasAnyAncestor`
as a use case. However, **no existing test in the codebase uses
`hasAnyAncestor`**.

### Decision

The overload is a reasonable investment: it costs ~20 lines and makes future
selectors possible without code changes. Keep it. Document here that it was
evaluated and deferred to future demand.

**Re-evaluation trigger:** A test needs to select a node by ancestor
relationship rather than by tag or text content.

---

## 4. `DesktopNavigation.kt` is approaching a "god helper" smell

### Finding

`DesktopNavigation.kt` is ~280 lines and contains 6 distinct helpers:

| Helper | Purpose |
|---|---|
| `TIMEOUT_MS` | constant |
| `shellControls` | content description constants |
| `navigateTo` | navigation helper |
| `awaitTag(tag: String)` | wait for testTag |
| `awaitTag(matcher: SemanticsMatcher)` | wait for arbitrary matcher |
| `awaitAnyDisplayed(tag: String)` | wait for at least one visible node |
| `awaitGone(tag: String)` | wait for node to disappear |
| `explainMissingTag(...)` | failure message formatter |
| `tapTab(name: String)` | tab navigation |

### Decision

No action required today. All helpers are cohesive (they serve the same
consumer: desktop Compose UI tests). Splitting them would increase indirection
for no immediate benefit.

**Re-evaluation trigger:** A new helper that does NOT share the same
consumer (e.g., a helper for Android instrumented tests, or a helper for
non-Compose desktop tests). At that point extract the unrelated helpers into
their own file.

---

## 5. `ContextMenuTest` and `MenuBarTest` remain outside the test harness

### Finding

`DesktopTestHarnessEnforcementTest` allowlists `MenuBarTest` and
`ContextMenuTest` because they call `runDesktopComposeUiTest` directly,
bypassing `runDesktopAppTest`. This means they receive no failure bundle
(screenshot, DB state, Kermit log) on test failure.

- `ContextMenuTest`: smoke-tests a composable in isolation via
  `runDesktopComposeUiTest`. No Koin, no database. Failure bundle would
  be empty in this context anyway.
- `MenuBarTest`: smoke-test for menu rendering. Same reasoning.

### Decision

Keep the allowlist entry. The tests are smoke tests at the Compose layer;
they are not testing the full app integration where the harness adds value.

**Risk:** If either test grows to test full app integration, the
`DesktopTestHarnessEnforcementTest` allowlist must be updated to either
migrate the test to `runDesktopAppTest` or extract a new harness that
captures the relevant failure context.

---

## 6. `awaitAnyDisplayed` has exactly one call site

### Finding

`awaitAnyDisplayed` (which handles the "pager/list with multiple composed
nodes" case) is called only from `CalendarFlowTest`. It was introduced in
`main` before the Ultron spike merged.

### Decision

No action. The function is correctly scoped for its single consumer. If a
second call site appears, consider promoting its documentation and adding a
unit test to `DesktopNavigation` itself.

---

## Action items

- [x] Fix unnecessary cast in `CreateTaskFlowTest.kt:139`
- [x] Consolidate `ContextMenuTest.kt` `@OptIn` to file-level
- [ ] (Optional) Migrate remaining class-level `@OptIn` to file-level in
        existing files when touching them for other reasons
