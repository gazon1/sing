---
title: "The Maestro cleanup never matched, so a red nightly reported one defect as forty-three"
date: 2026-10-20
status: accepted
tags: [testing, e2e, maestro, ci]
---

## Context

The nightly Maestro run of 2026-10-20 (`39383383740`, commit `fb8b0699`) was red.
All four shards of the full suite failed, and **43 flows failed between them** —
not the three or four that the summary of the run suggests, and not a timeout
problem. The failures are spread across every feature area and interleave with
passing flows: shard 2 passed its first seven flows (`01-change-sync-interval`,
`01-first-run-anonymous`, `01-offline-create-survives-reconnect`, `01-restore`,
`01-theme-toggle`, `02-empty-notes`, `02-notifications`) and then failed
`pomodoro-start-focus-task`, passed `03-saved-views-crud`, and failed
`04-delete`; shard 1 failed from its very first flow.

The failures are of two kinds, and both are "the element is not where the flow
expected it":

- **A seeded fixture is not on the screen.** `Element not found: Id matching
  regex: task_item_attachtesttask2026`, `task_item_emptyurltask2026`,
  `task_item_persistencetask2026`, `pomodoro_task_chip_buy_milk`.
- **A screen is showing something else.** `Assertion is false: id:
  search_empty_placeholder is visible`, `task_detail_title`, `note_editor_title_input`,
  `agenda_section_today`, `"October 2026" is visible`, `auth_save_server_button`.

The screenshots in the `maestro-failures-*` artifacts settle it. The `search-clear`
screenshot shows the search screen with an **empty query** and a list of tasks
that this flow never created:

```
r4797858390 Anonymous task          ← seeded by 01-first-run-anonymous
r4798445200_Offline task            ← seeded by 01-offline-create-survives-reconnect
Buy milk                            ← the fixed fixture
Buy milk                            ← the fixed fixture, again
r4801926640_ClearSearchTask202…     ← this flow's own seed
```

`r4797858390` is the run id of a flow that **passed**, in the same shard, minutes
earlier. Its fixture was still on the device. So were two copies of the fixed
`Buy milk` fixture.

The `pomodoro-start-focus-task` screenshot agrees: the task chips show
`r4797858390 Anonymous task` and `r4798445200_Off…` — again, other flows' data,
not the `Buy milk` task the flow wanted to tap.

## Idea

The suite has a fixture-isolation model, and it is written down:
`Maestro/helpers/launch-clean.yaml` says that the database is deliberately not
wiped between flows, and that the accumulation is handled "by the runner":

> `run-maestro` stamps each attempt's title with a per-run id and, on success,
> sweeps exactly those rows plus the fixed seed titles via
> `todo-debug://seed?cleanup=…`.

And `DebugSeedActivity` documents the other end of the contract: `cleanup=` is a
comma-separated list of **prefixes**, and a task, note or project whose title
starts with one of them is deleted.

So the model is: each flow seeds under its own run id, and the runner sweeps
that id's rows when the flow passes. The nightly's evidence says the sweep never
happened.

- **A.** The app is broken. The flows fail on real product defects, and the
  screenshots are just what a broken app looks like.
- **B.** The runner's cleanup link is malformed, so it matches nothing, and
  every fixture survives for the rest of the shard.
- **C.** The cleanup only runs on success, so the *first* failing flow's
  fixtures leak into every flow after it — the cascade, on top of A or B.

## Decision

**B and C, both, with the reasoning recorded.**

- **The cleanup link is built wrong and matched nothing.** The value was
  assembled as `f"'{run_id},Buy%20milk'"` — shell-style quotes around the list
  and a space that was already percent-encoded — and then URL-quoted *again*.
  The app therefore received the literal string `'r4797858390,Buy%20milk'` and
  split it into the prefixes `"'r4797858390"` and `"Buy%20milk'"`. The app
  matches titles by prefix, so the run id was preceded by an apostrophe and the
  fixed title carried a trailing apostrophe and an encoded space: **neither
  matched any title, ever.** The fix is to quote the list as a whole and let
  `urllib.parse.quote` do the encoding.
- **A failing flow's fixtures are swept before the *next* flow starts**, not
  left for the rest of the shard. Cleaning on success only is deliberate — a
  failing flow keeps its data so the failure can be triaged against it — and
  that decision stands. But "until the next flow starts" is long enough: the
  artifacts (screenshot, screen tree, logcat) are captured immediately after the
  failure and are what triage actually reads.
- **The fix is guarded by a test that states the property, not the
  implementation**: the prefixes the app will read must be real prefixes of the
  titles that were seeded. Four of the new tests fail against the old code.

## Rationale

- **A is refuted by the data, not by reasoning.** The seeded rows in the
  screenshot carry the run ids of flows that had already passed, in the same
  shard. A product defect does not invent another flow's run id.
- **The defect was invisible, which is why it survived.** Nothing failed at the
  point of the defect. The link was sent, the adb command succeeded, the app
  accepted it and deleted nothing. The symptom appeared three steps later, in
  another flow, as an assertion about an empty list. A defect whose evidence is
  in a different flow's screenshot is a defect that reads as a product problem
  until somebody looks at the run ids in the data.
- **The nightly was the only place this could show.** The smoke suite has seven
  flows, seeds less, and asserts less about emptiness — which is why the same
  commit was green in the push run (`39339605222`, smoke) and red in the
  scheduled run (`39383383740`, full). The two runs were never comparable: one
  ran a seventh of the suite.
- **Cleaning on success only was never the defect; cleaning on success only
  *forever* was.** The deliberate part is "a failing flow keeps its fixtures for
  triage". The window for that triage is the seconds between the failure and
  the next flow — the artifacts are captured in between. Carrying the fixtures
  for the rest of the shard serves no triage and breaks every flow that
  follows.
- **The one-sided floor has the same shape.** `config/docs/instrumented-test-floor.txt`
  sat at 5/19 while the suite was 6/21, and the gate compared 21 against 19,
  found no drop, and stayed green. A check that only fires in one direction
  stops asking its question the moment reality moves in the other one. That is
  why the floor ratchets in both directions now
  (`2026-10-20-the-instrumented-floor-is-a-ratchet.md`).

## Consequences

- **The full nightly suite should be materially greener.** Not provably green:
  the run that produced this evidence is a single observation, and the flows
  that failed for reasons of their own — a fixture that never arrived, a screen
  that was not where the flow expected it — cannot be told apart from the
  cascade without another run.
- **Some of the failures are probably not the cascade.** `search-clear` expects
  an empty query to return the search screen's idle prompt, while the screenshot
  shows the app showing the task list instead. That is a product/flow mismatch
  that the cleanup fix does not touch, and it is recorded as an open item
  rather than as a fix: whether the search screen should show the prompt or the
  list for an empty query is a product decision, not a test-harness decision.
- **The runner's cleanup is now covered by tests that state a property.** Six
  tests in `scripts/tests/test_run_maestro.py`: the run id is one of the
  prefixes, a seeded title starts with one of them, the fixed fixture is swept,
  the prefixes carry no quoting, the `all` mode still sweeps everything, and
  the `off` mode still sends nothing. Four of them fail against the old code.
- **The suite's independence is now a property of the runner rather than a
  claim in a helper's comment.** Before, the comment described a contract the
  runner did not keep; now the runner keeps it and a test says so.
- **A failing flow still keeps its fixtures for triage** — until the next flow
  starts. That is the window the decision keeps, and it is narrower than the
  one that was in force.

## Links

- `scripts/src/emulator/maestro/_fixtures.py` — the link that matched nothing
- `scripts/src/emulator/maestro/_cli.py` — the sweep of a failed flow's fixtures
- `scripts/tests/test_run_maestro.py` — the property tests
- `Maestro/helpers/launch-clean.yaml` — the isolation model, as documented
- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt` —
  the other end of the contract: `cleanup=` is a list of title prefixes
- `docs/decisions/2026-10-20-android-instrumented-smoke-on-every-pr.md` — the
  device tier's own two tiers, and why the emulator stays out of the gate
