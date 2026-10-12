# Diagnosing a Maestro flow failure

Load this when a flow has already failed and you need to find out why. Authoring
a flow — selectors, seeding, scrolling, syntax — lives in `SKILL.md`.

## Root causes of known failures (CI run 38223116125)

| Class | Root cause | Flows | Fix |
|---|---|---|---|
| A | Hardcoded swipe coords out-of-bounds on phone AVD | `backup-round-trip`, `smoke-settings-cycle-tabs` | `scrollUntilVisible direction: UP` |
| B | Missing `nav_menu_button` + `scrollUntilVisible` before `menu_settings` | `calendar-sync-provider-toggle`, `calendar-sync-system-toggle`, `calendar-sync-system-unavailable`, `sync-change-interval`, `negative-empty-tags` | `scrollUntilVisible element.id: menu_settings direction: DOWN timeout: 10000` |
| C | `menu_notes` below fold + 5 s timeout | `notes-edit-body`, `notes-filter-chips`, `notes-create-note`, `negative-empty-notes` | Same pattern as class B |
| D | `waitForAnimationToEnd` 2000 ms after PORTRAIT rotation | `lifecycle-rotate-in-editor` | 2000 ms → 5000 ms |
| E | Seed/state leakage, wrong testTag, agenda section timing | 4 flows | Fixed — see rows below |
| F | Action row laid out past the screen edge | `agenda-smart-lists` | `SheetScaffold` column made scrollable |

### Class E resolved — three separate defects

Only one of the four flows was a test problem; the other two were real bugs. All
four pass on `emulator-5554` with a `pm clear` slate.

| Flow | Actual cause | Owner |
|---|---|---|
| `calendar-view-mode` | fixture typing + wrong testTag | test |
| `settings-cycle-tabs` | fixture typing + wrong testTag | test |
| `agenda-smart-lists` | DatePicker actions off-screen | **product** |
| `auth-first-run-anonymous` | seeded row invisible until re-query | harness |

## A selector that cannot be reached

`Not found` on an element the source proves exists is a **layout** bug, not a
selector bug. Two causes, and the hierarchy dump separates them:

- **Wrong id or casing** — the element is present under a different tag. Compare
  against `Maestro/TAGS.md`.
- **Laid out off-screen** — the element is in the composition but sits below the
  screen edge, so it is **absent from the accessibility tree** and no selector or
  coordinate tap can reach it.

The second is a real product defect. `DatePickerSheet` put a 600 dp body plus its
actions row in a non-scrolling column, and on a 1080x2400 phone Clear/Cancel/OK
fell below the edge. The flow had been working around it with a coordinate tap at a
guessed `(920, 1390)` that missed. Making `SheetScaffold`'s column scrollable fixed
the touch target for users and tests together.

When a flow cannot tap something the code plainly wires up, check the geometry
before rewriting the selector.

## Debugging a failure

1. **Crash or assertion?** `adb -s emulator-5554 logcat -d -b crash | grep -A30
   "FATAL EXCEPTION"`. An app crash surfaces as the system "keeps stopping"
   dialog on screen and every later step failing — the exception names the real
   bug (three shipped crashes were found exactly this way).
2. **Assertion failure:** read the captured hierarchy —
   `~/.maestro/tests/<timestamp>/<flow-name>/screen-hierarchy/<step>.json`.
   Extract what was actually on screen:
   ```bash
   grep -o '"resource-id" : "[^"]*"' <step>.json | sort -u
   grep -o '"text" : "[^"]*"' <step>.json | sort -u
   ```
   Compare against `Maestro/TAGS.md` — usually the id is mis-cased, below the
   fold, or on a screen the previous step never opened.
3. **Nothing on screen but the home screen** — the app crashed earlier in the
   flow; go back to step 1.
4. **The Maestro XML has no per-step detail.** `maestro-failures-*/maestro.log`
   shows only Maestro's own polling output; the Compose state needs logcat. Use
   `scripts/run-maestro.sh`, which writes a raw framebuffer screenshot and a
   logcat dump per failed flow into `build/maestro-results/<flow>/`.

## Retry strategy: when to fix vs when to retry

**Fix the root cause, not the symptom.** A flow that fails because an element
was not visible after 5 s is telling you the element needs 5+ s on that device
— the retry just delays that signal.

The only retry mechanism is script-level: `MAX_RETRIES=2` in `run-maestro.sh`,
which covers device disconnects. Retry a flow only when the element has genuine
timing variance (cold start), and a `known-bug` tag marks the flow while a real fix
is tracked.

Do NOT use retry when:
- Multiple flows fail simultaneously with the same selector — this is a cascade,
  not independent variance. Check `seed-task.yaml` first.
- The failure is `Not found` rather than `Not visible` — retry does not help.

## Seed cascade detection

The most common cascade failure mode in this suite: `seed-task.yaml` times out,
`task_item_buy_milk` never appears, and 14 flows fail in the same CI run with
`task_item_buy_milk not visible` — but only the first failure is in
`seed-task.yaml`. Every other flow just used that fixture.

**How to recognise it:**
- 14 flows all failing with the same `Not visible: task_item_buy_milk` at the
  same step position (immediately after seed).
- The failure appeared after a CI runner was under load (seed takes longer when
  the host is busy).
- The seed-task itself passed on a previous run.

**Correct fix:** Increase the timeout in `seed-task.yaml` — do NOT add retry
wrappers to each consumer flow.

## CI vs local timing

The CI emulator runs on a shared host where gradle daemons, sandbox processes,
and the emulator itself compete for CPU. A flow that takes 8 s to seed a task
locally can take 25 s on CI. All hard timeouts should be calibrated against
CI, not local dev speed.

Practical multipliers observed on this CI host:

| Operation | Local (fast) | CI (loaded) | Safe timeout |
|---|---|---|---|
| App cold start | ~5 s | ~20 s | 30 000 ms |
| `waitForAnimationToEnd` after PORTRAIT | ~1 500 ms | ~4 500 ms | 5 000 ms |
| `extendedWaitUntil` for a visible element | < 2 s | < 8 s | 10 000 ms |
| Menu sheet scroll + tap | < 1 s | < 4 s | 10 000 ms |

When in doubt, use the CI-safe value. A timeout that is slightly too long
costs at most the duration of the wait; a timeout that is slightly too short
produces a cascade of failures that requires a full re-run to diagnose.