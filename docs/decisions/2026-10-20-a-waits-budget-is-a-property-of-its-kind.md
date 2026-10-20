---
title: "A wait's budget belongs to the kind of element it waits for"
date: 2026-10-20
status: accepted
tags: [testing, e2e, maestro, ci]
---

## Context

The Maestro flows carry 471 waits with a timeout. They carry **eleven different
timeouts**, and the number a wait got depended on which flow asked:

| timeout | waits |
|---|---|
| 8000 | 174 |
| 5000 | 161 |
| 10000 | 49 |
| 30000 | 44 |
| 15000 | 29 |
| 2000 / 40000 / 1000 / 500 / 3000 / 1500 / 20000 | 1–5 each |

The same element was waited for with different budgets in different flows.
`menu_settings` existed with 5 s, 8 s and 10 s. `task_editor_title_input` with
3 s, 5 s, 8 s and 15 s. `task_item_buy_milk` with 5 s, 8 s, 10 s and 15 s.

None of those numbers was derived from anything. They were copied, and a copy
carries no reason: there is no record of why one flow gave a menu item 5 s and
another gave it 10 s, because there was nothing to record. A timeout with no
reason behind it is a superstition with a number attached, and it is the same
defect as the instrumented suite's shell wait, one layer down — where a helper
learned that 8 s was not enough on a loaded CI runner and moved to 30 s while
the rest of the suite kept 8 s
(`2026-10-20-one-written-wait-budget.md`).

The 471 waits cover 237 distinct element ids. The measurements behind the
numbers:

- `menu_settings`: 27 waits at 5 s, 13 at 8 s, 8 at 10 s, 1 at 30 s.
- `nav_menu_button`: 24 waits, all at 5 s.
- `task_editor_title_input`: 25 at 5 s, 2 at 15 s, 1 each at 3 s, 2 s and 8 s.
- `task_item_buy_milk`: 11 at 8 s, 10 at 5 s, 1 each at 10 s and 15 s.
- `tasks_fab`: 3 at 5 s, 4 at 8 s.

## Idea

- **A.** Keep the per-flow numbers and document the best of them.
- **B.** Give every *element* its own budget. 237 budgets, one per id, each with
  its own reason. The consolidation of the duplicates is lost, and a new element
  starts with no budget at all.
- **C.** Give every *kind* of element a budget. A flow that waits for a menu item
  waits for it for the menu's budget, everywhere, and the budget is written down
  once with its reason.

## Decision

**C.** The budget is a property of the kind of element, held in one catalogue.

- **`config/docs/ui-wait-budgets.txt` gains a second section**: `kind <name>
  <milliseconds>`, one line per kind. The first section — the instrumented
  suite's `shell`, `tab` and `device` budgets — stays, and the cross-layer rule
  between the sections stays load-bearing: the Maestro `shell` kind MUST equal
  the instrumented `shell` budget, because both layers wait for the same
  screen.
- **The kinds are `shell`, `boot`, `tab`, `menu`, `field`, `button`, `item`,
  `dialog`, `text` and `network`.** They are kinds of *element*, not of screen
  and not of flow: the menu sheet and the item in it are both `menu`; a FAB, a
  save button and a switch are all `button`.
- **`config/docs/maestro-element-kinds.txt` maps the 137 waited-for elements to
  their kinds**, and the mapping is **literal**: the template id
  (`task_item_${RUN_ID}_anonymous_task`) is the key, not a pattern. A pattern
  matcher would be a second, weaker promise — the substitution changes the
  value, not the element's shape.
- **A kind's budget is the mode of the timeouts the tree used for that kind,
  and never less than 5 s.** An outlier is aligned to the budget. A budget that
  is wrong for everybody is a decision to make once, in the catalogue, where the
  reasoning can be written down.
- **Three kinds are not their own mode, and say why in the file:** `shell` is
  fixed by the instrumented budget; `boot` is a cold start and takes the
  shell's number; `network` is the slowest wait in its kind (20 s), because a
  server answer has no upper bound and the mode would cut a slow answer in
  half. `tab` is 10 s, above its mode of 8 s, to match the instrumented layer's
  budget for the same kind of wait.
- **The gate holds the tree to it.** `scripts/check-ui-wait-budgets.py` fails
  when a wait disagrees with its kind's budget, when a wait has no timeout, when
  an element has no kind, when a kind has no budget, and when the cross-layer
  shell rule is broken. It reads the flows' `extendedWaitUntil` and
  `scrollUntilVisible` — the commands that carry both an element id and a
  timeout. `assertVisible` and `tapOn` are not waits: they have no timeout to
  disagree with.
- **204 waits were re-timed** across 74 flow files: 107 down, 97 up, 227
  unchanged. Every wait that went down is listed in the review note below.

## Rationale

- **A budget that belongs to the element is the same number everywhere, which
  is what makes it a property rather than a habit.** The duplicate is not
  "resolved" by this change; it is *impossible*: a second wait for the same
  kind with a different number is now a gate failure.
- **Kinds, not elements, because 237 budgets is not a budget system.** B would
  have produced 237 numbers, each of which would need its own reason, and the
  consolidation that the duplicates show — the same menu item waited for five
  ways — would be preserved as five separate records. The kinds are the level at
  which a number has a reason: "a menu sheet takes 5 s to appear" is a claim
  about the app; "this flow's menu item takes 5 s" is a claim about a copy.
- **The mode is the honest starting point.** The mode is what the tree mostly
  did, and it is what the flows were written against. The budget is not
  invented from nothing; it is the existing behaviour, written down, with the
  outliers aligned to it. The exceptions are named so that a reader can see
  where the mode was overridden and why.
- **The cross-layer rule is what stops the file from being two files.** The
  instrumented suite and the Maestro flows are separate runners and cannot share
  a constant — but they wait for the *same screen*. The catalogue holds both,
  and the shell rule ties the two numbers together, so the drift that made the
  device workflow red for days cannot come back through a second entry.
- **The mapping is literal because a pattern is a promise nobody checks.** The
  gate holds the mapping file to the tree: an element nobody budgeted is a
  finding. A pattern that happened to match would make the mapping a set of
  guesses about ids nobody has looked at.
- **`network` is a kind of its own because "the answer took a while" is a
  different fact from "the widget took a while".** A budget of 8 s for an
  element that only appears after a server round-trip is a budget that will be
  raised by hand, in whichever flow happened to fail first.

## Consequences

- **The risky moves are the downwards ones, and they are listed.** The largest
  are `auth_email_input` (15 s → 5 s, seven waits), the whole `menu` kind
  (10 s and 8 s → 5 s, twenty-one waits), `saved_agenda_name_input` (8 s → 5 s,
  twelve waits), `result_dialog` and the three `health_bucket_*` (15 s → 8 s),
  and `auth_continue_offline_button` (40 s → 30 s). Each is a *deliberate*
  alignment to the kind's mode, and each is a place where the mode was wrong
  for a specific case: a menu sheet that scrolls is a different wait from a
  menu sheet that is already open.
- **The catalogue is a second file to keep true.** `maestro-element-kinds.txt`
  has to cover every element the flows wait for, and a new element has to be
  classified before the gate lets the flow through. That is friction in the
  right direction: the alternative was a timeout nobody chose.
- **A new kind is a decision.** Adding one is a change to the catalogue with a
  reason, in a file whose whole purpose is to hold reasons.
- **The gate found a real bug while it was being written**: `scan()` never
  passed `--root` to the Maestro scan, so the pre-push hook checked the
  *worktree* rather than the tree being pushed — a check that could pass on one
  commit and be meaningless on the next.
- **The instrumented layer's own budgets are unchanged**, and the gate checks
  them as before. This change is about the Maestro layer, which is the one that
  had drifted against itself.

## Links

- `config/docs/ui-wait-budgets.txt` — both sections, and the reasons
- `config/docs/maestro-element-kinds.txt` — the element-to-kind mapping
- `scripts/check-ui-wait-budgets.py` — the gate
- `Maestro/CONVENTIONS.md` — the convention, as the flow authors see it
- `2026-10-20-one-written-wait-budget.md` — the same defect in the instrumented
  layer, and the cross-layer rule this catalogue continues
- `2026-10-20-the-maestro-cleanup-never-matched.md` — the other half of the
  nightly's red: the flows were not independent of each other either
