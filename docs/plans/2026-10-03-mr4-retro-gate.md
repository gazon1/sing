# MR-4 Retro-Gate

**Phase 4 / MR-4** — desktop flow matrix A–E — committed as `d248c8a4`.

---

## Gate Results

| Gate | Result |
|------|--------|
| `:desktopApp:test` (89 tests) | ✅ BUILD SUCCESSFUL |
| AgendaReachabilityFlowTest (NEW, 7) | ✅ All pass |
| SavedAgendaEditFlowTest (NEW, 5) | ✅ All pass |
| SavedViewsFlowTest (expanded 2→8) | ✅ All pass |
| HarnessConventionTest (checkA11y) | ✅ All flow tests opt in |

---

## Problems Found & Fixed During MR-4

### 1. Desktop AWT menu bar collides with `onNodeWithText("Edit")` (CRITICAL for future tests)

**Files:** `SavedViewsFlowTest.kt`, `SavedAgendaEditFlowTest.kt`

**Problem:** The desktop shell renders an AWT menu bar (File / Edit / View / Help) whose labels enter the semantics tree as plain text nodes at the top of every screen. `onNodeWithText("Edit")` therefore matched 2 nodes (menu bar label + dropdown item) and threw. Worse, the menu-bar "Edit" has **no click action**, so text-only matchers cannot disambiguate by behavior either.

**Fix:** Match dropdown items by text AND click action:
```kotlin
onAllNodes(hasText("Edit") and hasClickAction()).onFirst()
```

**Ripple:** The SavedAgendaCard's "⋮" overflow button is text-only (no testTag, no contentDescription) — it is also unreachable by a11y tooling. Recorded as a backlog item below.

---

### 2. Edit-mode Save/Delete buttons render below the 768px window fold

**File:** `SavedAgendaEditFlowTest.kt`

**Problem:** The edit-mode LazyColumn lists the Inbox preset's 8 section rows; the Save/Delete buttons are the last `item {}`. On the 1024×768 harness window they fall below the fold. `performClick()` on the off-screen node silently injected clicks at out-of-window coordinates — no error, no effect, then `awaitTagGone` timed out 5s later. Diagnosed via the harness's auto-captured failure screenshot (build/diagnostics).

**Fix:** `scrollEditorToBottom()` helper — `onAllNodes(hasScrollAction()).onFirst().performMouseInput { scroll(4_000f) }` (positive amount = scroll down on desktop).

**Ripple:** The buttons being unreachable without scrolling is arguably a real UX issue on small screens (the buttons are the primary action of the form). Backlog: consider pinning the action row or reducing section-row height.

---

### 3. Agenda Results screen hides empty sections

**File:** `SavedViewsFlowTest.kt`

**Problem:** Tests that opened a saved view with no matching tasks expected the "No Date" section to render — it never does. Sections with zero tasks are hidden (by design), so the awaitTag timed out with "All available tags (0 total)".

**Fix:** Seed an undated task (`tasks(koin).givenUndated(...)`) before tapping the card, mirroring `OpenSavedViewShowsMatchingTasksFlowTest`.

---

### 4. List-level delete is immediate — no confirm dialog (verified MR-1 policy)

**File:** `SavedViewsFlowTest.kt`

**Problem:** The delete test initially assumed a ConfirmActionDialog on the list screen and clicked "Delete" twice; the second lookup failed because the view was already gone.

**Finding (confirmed in source):** list-card menu Delete → `SavedAgendaListIntent.Delete` → immediate repository delete (snackbar feedback); the ConfirmActionDialog exists only in Edit mode (`SavedAgendaScreen.kt:150`, tagged `TestTags.Dialog.CONFIRM`). This matches the MR-1 policy: non-cascading deletes → immediate + undo, destructive/cascading → confirm.

**Fix:** Test asserts immediate removal; edit-mode test uses `TestTags.Dialog.CONFIRM`.

---

### 5. `HarnessConventionTest` enforces `checkA11y = true` — new tests must opt in

**Files:** `AgendaReachabilityFlowTest.kt`, `SavedAgendaEditFlowTest.kt`

**Problem:** New tests used `runDesktopAppTest { }` without the a11y check; the harness convention test failed on the whole suite.

**Fix:** All flow tests now pass `checkA11y = true`. Convention verified working — it catches new files automatically.

---

### 6. `onNode`/`onAllNodes` are receiver members, not importable extensions

**Problem:** `import androidx.compose.ui.test.onNode` does not resolve on the desktop test API — helpers must be extensions of `DesktopComposeUiTest` to use them.

**Fix:** `scrollEditorToBottom()` declared as `private fun DesktopComposeUiTest.scrollEditorToBottom()`.

---

## Deferred to ADR / Backlog

| Item | Reason | Where |
|------|--------|-------|
| "⋮" overflow button unreachable by a11y (text-only, no tag/description) | Needs UI change (add contentDescription + testTag) | backlog |
| Save/Delete below the fold in edit mode on small screens | UX consideration, needs product decision | backlog |
| FakeClock wiring into `runDesktopAppTest` | Deferred since MR-2; flow tests still use `todayInSystemZone()` | backlog |
| `SAVED_AGENDA_CANCEL` tag | No Cancel button exists in the editor; back = cancel | backlog |

---

## MR-4 Summary

**Suite coverage now (desktop):**
- A: tab definition (3) + reachability (7) + badge policy (5) + open-task (3)
- B: saved-views list (8)
- C: create flow (1)
- D: edit flow (5)
- E: open-saved-view-shows-matching-tasks (1)

**Not done (deferred from plan):**
- Persistence-restart flow test (E): the harness recreates the Koin graph per test; simulating a restart requires a two-phase harness — deferred to backlog
- Copy-to-profile flow test: ProfilePickerSheet interaction deferred to MR-5 Maestro journey 08
