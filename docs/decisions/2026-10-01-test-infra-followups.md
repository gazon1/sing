---
description: Follow-up observations and deferred items discovered during test-infra ratchet work
status: active
created: 2026-10-01
tags: [test-infra, Maestro, TestTags, follow-up]
---

# Test-infra follow-ups (2026-10-01)

## Context

During the `refactor/testinfra-ratchet` MR work (PR #19), three follow-up items were
discovered that do not block the MR but represent real debt or information that
should be captured for future agents.

---

## 1. Maestro flow bug: `task_action_mark_completed` → `task_action_mark_as_completed` ✅ Fixed

**What.** `Maestro/flows/tasks/05-archive-via-menu.yaml:37` used `task_action_mark_completed`
as an assertion ID, but `TestTags.taskAction("Mark as completed")` produces
`task_action_mark_as_completed` (slug of "Mark as completed").

**Impact.** The flow assertion `assertVisible: id: task_action_mark_completed` was checking
for a tag that never appears in the UI. The actual tag `task_action_mark_as_completed`
was never asserted in this flow — it silently passed because the assertion was
searching for the wrong ID.

**Fix.** Changed to `task_action_mark_as_completed`. Committed directly to the MR branch.

**Verification.**
```bash
python3 Maestro/scripts/check-tags.sh  # passes
# slug("Mark as completed") = "mark_as_completed" ✓
```

---

## 2. `knownUnapplied` EditorOverflow entries: design, not bug

**Observation.** All five `EditorOverflow.*` constants (`DELETE`, `ARCHIVE`, `RESTORE`,
`PIN`, `UNPIN`) are documented in `knownUnapplied` as "no call site — the overflow menu
renders rows through `TestTags.taskAction(action)`". This means the constants are
dead code: `TaskContextMenuSheet` calls `TestTags.taskAction(label)` with the
display label, never the `EditorOverflow.*` constants directly.

**Is this a problem?** No, by design. The constants exist as a named vocabulary for
the overflow actions, but the actual tag resolution is done through `taskAction()`
which takes the human-readable label. The `EditorOverflow.*` constants are a
documentation/naming layer, not a runtime-used one.

**Why keep them?** They appear in `TAGS.md` and `Maestro/TAGS.md` as the canonical
source of the tag value (`overflow_archive`, etc.). Removing them would break
documentation without any runtime benefit.

**No action required.** This is working-as-documented.

---

## 3. Two parallel tag-validation systems have a 52-ID gap

**What.** `TestTagsWiringTest` (Kotlin, JVM, `:shared:jvmTest`) validates that every
TestTags constant is applied somewhere in `commonMain + androidMain + jvmMain`.
`Maestro/scripts/check-tags.sh` (bash + Python, runs in CI) validates that every
`id:` in Maestro YAML flows is either a TestTags constant or matches a slug-pattern.

Both systems use allow-lists for "raw strings before TestTags migration". The
gap between them is 52 IDs: Maestro uses literal slug-values (e.g. `menu_ai_chat`,
`nav_tab_calendar`) that are slug-consistent with the dynamic `menu_${slug(label)}`
and `nav_tab_${slug(title)}` functions, but are never verified against the actual
function output.

**Examples of gap IDs (Maestro → TestTags catalog):**

| Maestro `id:` | Would be produced by | In TestTags? |
|---|---|---|
| `menu_ai_chat` | `menuItem("AI Chat")` | No constant — only `fun menuItem(label)` |
| `nav_tab_inbox` | `navTab("Inbox")` | No constant — only `fun navTab(title)` |
| `note_item_by_title_alpha` | `noteItemByTitle("Alpha")` | No constant — only `fun noteItemByTitle(title)` |
| `pomodoro_play_pause_button` | static — no function | No |
| `dialog_title_priority` | `dialogTitle("priority")` | No constant — only `fun dialogTitle(key)` |

**Why it works today.** The Maestro IDs happen to be slug-consistent, so when a
flow taps `id: menu_ai_chat`, Compose finds the node because the actual
`testTag = menuItem("AI Chat")` evaluates to `menu_ai_chat`. The system is
correct by coincidence, not by verification.

**Is this a problem?** A latent one. If a future developer changes the label
text (e.g. "AI Chat" → "Assistant") without updating the Maestro flow, the flow
will silently target the wrong ID. The `check-tags.sh` allow-list masks this.

**What would fix it.** A unified tag registry that both Kotlin tests and Maestro
flows consume, with explicit constants for every non-dynamic ID and slug-pattern
verification for dynamic ones. This is a large refactor (53 Maestro files, many
allow-list entries) — not for this MR.

**Documented in.** This ADR.

---

## 4. `SNACKBAR_SAVED` known-unapplied entry: stale reason

**Observation.** The `knownUnapplied` entry for `SNACKBAR_SAVED` says:
> "referenced by Maestro/flows/agenda/03-saved-views-crud.yaml, which waits on a
> snackbar the screen never shows"

**Current state.** The flow does use `snackbar_saved` and does wait for it.
The screen is `AgendaScreen`, which does show a snackbar on save. The comment
appears to be stale or was describing a past bug that has since been fixed.

**No action required in this MR.** The `knownUnapplied` entry is still valid
(the SnackBar showing is a `ResultDialog` UI element, not a test-tag issue),
but the reason text should be audited against the current flow behavior.

---

## 5. ktlint `FunctionSignature` / `WrappingRule` conflict in `TestTagsWiringTest`

**What.** The function:
```kotlin
private fun declaredConstants(): List<Pair<String, String>> =
    TestTagsCatalog.staticTags()
```
triggers two conflicting ktlint rules:
- `WrappingRule`: putting `= TestTagsCatalog.staticTags()` on the same line as
  the signature exceeds the 120-char line limit
- `FunctionSignature`: putting `=` on a new line triggers "first line of body
  expression fits on same line as function signature"

**Current fix.** `@Suppress("FunctionSignature")` on the function, with a
concise comment explaining the conflict. The `WrappingRule` is satisfied by the
newline; `FunctionSignature` is suppressed.

**This is a ktlint bug or missing config.** The two rules should not conflict
on a function that returns a single expression. Filed as: **out of scope for
this MR — ktlint version `2.0.0-alpha.3` may have this interaction; upgrade
path should re-evaluate.**

---

## Open items (not fixed in this MR)

| # | Item | Trigger | Priority |
|---|---|---|---|
| A | Audit `SNACKBAR_SAVED` reason text vs. current flow behavior | This MR | Low |
| B | Consider collapsing 5 EditorOverflow entries into 1 with multi-line reason | This MR | Low (style only) |
| C | ktlint `FunctionSignature`/`WrappingRule` conflict | This MR | Low (ktlint upgrade) |
| D | Unified tag registry bridging Kotlin wiring test and Maestro allow-lists | Future MR | Medium (latent regression risk) |
