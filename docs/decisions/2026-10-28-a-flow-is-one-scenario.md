---
title: "A flow is one scenario, so the flows that look duplicated are not"
date: 2026-10-28
status: accepted
tags: ["e2e", "maestro", "traceability", "tooling"]
---

## Context

A review of the suite said six groups of flows duplicate each other and could
be merged, for about 35–45 minutes of a 2 h 30 m run:

- `negative/01..06` — "six near-identical 'navigate, assert one literal'";
- `attachments/01, 02, 04, 05` — "an identical 14-step preamble";
- `pomodoro/02, 03, 05` — "the same 5-step preamble, different last action";
- `negative/05` ⊂ `search/06`, and `system/search-finds-task` ⊂ `search/03`;
- `tasks/14-prefill-from-today` vs `tasks/15-prefill-from-inbox` — "differ
  only in source tab and expected section".

The review also measured the saving: 35–45 minutes, a quarter of the run.

## Idea

- **A.** Merge the duplicated flows, as listed.
- **B.** Leave the flows alone and say why.

## Decision

**B**, with one change: the ten flows that *looked* duplicated now declare the
scenario they carry.

**Why a merge is not available.** The traceability layer holds a rule that is
a hard error, not a preference (`scripts/src/traceability/links.py`):

> поток заявляет сценарии [...] — один запуск потока даёт один результат;
> разделите на два потока

One flow run produces one result. A scenario needs a result. So two scenarios
need two flows — even when the two flows assert the same things. Merging
`negative/05` into `search/06` would not merge two flows; it would delete
either the scenario `NEGATIVE-05` or the scenario `SEARCH-06` from the
repository's statement of what it verifies. That is a decision about the
capability corpus, not a cleanup of the suite, and it is the owner's.

**Why the flows looked duplicated.** Because their claims were invisible.
Nine of the ten — `NEGATIVE-01`..`NEGATIVE-06`, `POMODORO-02`,
`POMODORO-03` and `POMODORO-05` — had a scenario spec whose header named a
flow as its carrier, and the flow did not carry the tag. The tenth,
`SEARCH-01`, was counted as a claimed cell with no carrier named at all; this
commit names `system/search-finds-task` as its carrier and tags it. Ten
scenarios the repository had already written down read as uncovered, and ten
flows read as six-and-three copies of each other.

The ten flows now carry the tag their own spec names:

| Flow | Scenario |
|---|---|
| `negative/01-empty-inbox` … `negative/06-empty-tags` | `NEGATIVE-01` … `NEGATIVE-06` |
| `pomodoro/02-start-focus-task` | `POMODORO-02` |
| `pomodoro/03-pause-resume` | `POMODORO-03` |
| `pomodoro/05-stop-resets` | `POMODORO-05` |
| `system/search-finds-task` | `SEARCH-01` |

The carriers went from 76 to 86, and the ratchet in
`config/docs/traceability-ratchet.json` was lowered to the level the new
carriers reach (93 → 83 holes, 70 → 60 dark scenarios) so a regression is
caught there.

**Why the saving was smaller than the review said.** Measured on the device
this repository develops against: the runner's per-flow fixture tax is about
three seconds of explicit sleeps (the content wipe's one second, the session
reset's one second, and the adb round trips), and the app's cold start is
1.1 s (`am start -W`, `TotalTime: 1135`). The `extendedWaitUntil` in
`launch-clean.yaml` is a bounded poll, not a 30-second sleep — it returns as
soon as the shell is up, and the 30 s is the ceiling it waits *up to*. The
expensive part of a flow is Maestro's own per-invocation startup, measured at
about 9 s on a warm device.

So a merge buys one flow's fixed cost — call it 15–25 s — plus the preamble
it removes. Thirteen merges would have bought roughly 4–10 minutes of a
150-minute run, not 35–45. Against that: one failing merged flow hides every
assertion behind it, and the failure floor counts *flows*, so the debt
ratchet the repository keeps (`config/docs/maestro-failure-floor.txt`) gets
coarser with every merge.

## Rationale

- **The rule is already enforced.** `traceability validate` fails on a flow
  that claims two scenarios. A merge that satisfied it would have to delete a
  spec, and a spec is a statement about what the product does.
- **The saving is a quarter of what it was claimed to be**, and it is paid in
  the failure signal — the thing the suite exists to produce.
- **The duplication was in the bookkeeping, not in the flows.** `negative/01`
  asserts the Inbox is empty and `negative/02` asserts the notes list is
  empty; they are two behaviours, and the review's premise ("near-identical")
  was false. Declaring the claims is what makes the difference visible.
- **The precedent in the repository is narrow.** The one merge that had already
  happened (`3b69ec28`, the three health-bucket flows) merged three flows
  that asserted on the *same* screen, carried no scenario tags, and were
  replaced by one flow that carried none either.

## Consequences

- **Ten coverage holes closed**, and the traceability matrix and the ratchet
  say so.
- **The suites are the same size.** 117 flows, 27 of them tagged `smoke`.
- **A future merge is a corpus decision.** If two scenarios are genuinely the
  same behaviour, the way to merge them is to delete one spec and say so in
  the commit — not to delete a flow and leave a spec pointing at nothing.
- **The review's premise is worth re-testing against the spec corpus** rather
  than against the flow files: a flow that asserts nothing distinct is a
  missing spec, and a spec with no carrier is a missing tag.

## Links

- `scripts/src/traceability/links.py` — the one-scenario-per-flow rule
- `scripts/src/traceability/scenarios/` — the specs, and their named carriers
- `config/docs/traceability-ratchet.json` — the hole count, lowered to 83
- `scripts/src/emulator/maestro/_fixtures.py` — the per-flow fixture tax
- `Maestro/helpers/launch-clean.yaml` — the bounded wait, not a sleep
- `2026-10-21-each-flow-gets-a-clean-device.md` — why the isolation stays
- `2026-10-22-the-broad-scan-share-is-measured.md` — the house rule that a
  measured number beats an estimated one
