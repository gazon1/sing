---
title: A hole had two opposite mistakes in it; the matrix now says which supply a cell needs
date: 2026-10-05
status: accepted
tags: [traceability, testing, tcm, spec, gates]
---

## Context

One glyph, `○`, meant "claimed, nobody wrote a test". In one session that glyph
attracted **two opposite mistakes**, and each looked like the other's remedy.

`TASK-TIME-01` was narrowed from `[android, desktop]` to `[android]` with a
careful comment: a reachability probe found no time-tracking node on the
desktop task detail, so the UI was Android-only. Wrong — the feature was in
`commonMain` (ten files, repository and fake included) and the desktop screen
had silently stopped rendering it because a merge had put the two platform
graphs on different screens.

`SYNC-OFFLINE-01` claims `[android, desktop]` and its steps are "turn the
network off", "restore the network and repeat with a connection that comes and
goes every few seconds", and the check is that a **second device** does not end
up with duplicates. No single-device harness can observe that, on either tier.
The claim is true and no test-shaped supply exists.

Both drew as `○`. So the recorded remedy for "the feature is missing here" was
"narrow the claim", and the recorded remedy for "the claim is right but
unwritable" was "write a test". Nothing in the tooling could tell them apart,
and the only record of the intent was prose above a `targets:` list.

The lesson written after the first mistake was half a lesson: the skill said
*do not narrow a spec under a failed probe*. It said nothing about the reverse
— *do not claim a tier no harness can reach* — so the next agent repeating my
work would have had the symmetric trap waiting.

## Idea

A fifth cell state, carrying the **kind of supply**, plus a floor so the
classification cannot be used as a place to park holes.

The tempting version is a per-scenario note, because a hole is a hole and the
count does not care. That is exactly why it does not work: with one number, the
cheapest move is to make the un-actionable holes look different from the
actionable ones, and the count never notices.

## Decision

`unreachable:` in the spec — claimed targets that no automated carrier can
reach on that tier — and a `◇` glyph distinct from `○`.

Three properties, in order of how much they cost to get wrong:

1. **Still counted as a hole.** `Coverage.holes()` includes them.
   `Coverage.unreachable_holes()` is a *classification of* that set, not a
   smaller one. Otherwise marking a claim unreachable would be free.
2. **Must be a subset of `targets`.** A target nobody claimed owes nothing and
   cannot be unreachable. This is the invariant that stops the flag being used
   as a disguised narrowing — the exact mistake it was added to distinguish.
3. **Read from the spec, never inferred from a probe.** A failed probe measures
   the code in front of it. Inferring reachability from an observed miss is how
   `TASK-TIME-01` got narrowed in the first place.

And because 1 leaves a hole, a third ratchet metric: `unreachable_cells`, floor
6. A reclassification has to be paid for in one bucket or the other.

## Rationale

The generator refuses an unreachable target by name, and says why a probe would
be useless and that the honest alternative is to remove the field if it does not
belong. That is the piece that does the work at the moment somebody is about to
burn an afternoon: `just trace-carrier SYNC-OFFLINE-01` is where the next agent
would have started, and it now stops with an explanation instead of writing a
probe that cannot pass.

Only **6 of 32** holes are marked, and the choice was per target rather than
per area — `SYNC-STATUS-01` ("press it with the network off, and with no account
signed in") stays fully probe-able on desktop while its two-device neighbours
do not. Marking the whole sync area unreachable would have been one line and
would have been both unmeasured and a lie: a Compose test can turn the network
off on one device.

`SYNC-PROTO-01` is deliberately **not** marked. Its expectation says the
message does not exist yet, so its hole is an unbuilt feature, not an
unreachable tier — a third kind, which this field does not model. Recording it
here rather than inventing a fourth glyph for one scenario.

## Consequences

The queue is now sorted by supply: 26 holes want a test, 6 want a device farm or
a human. That is the number the next agent needs, and it was not derivable from
a count.

The `◇` glyph appears in the committed matrix, so the distinction is visible in
the artefact a reviewer reads, not only in the spec.

`CoverageCell` grew a third boolean beside `claimed`/`automated`/`deprecated`.
Three booleans for four-plus states is the shape that produced the `deprecated`
bug earlier this year — the cell could not express a state and the renderer
guessed — so the next addition should be a small enum, not a fourth boolean.
Noted rather than done, because nothing needs it yet and changing the shape
under three call sites during an unrelated change is its own risk.

## Links

- `infra/kiwi/scenarios/sync/{offline,profiles,incoming}` — the three marked specs
- `infra/kiwi/traceability/{spec,coverage,render}.py` — the field, the cell, the glyph
- `scripts/check-traceability-ratchet.py` — `unreachable_cells`
- `2026-10-05-one-task-detail-screen-after-a-merge-split-the-nav-graphs.md` — the mistake this generalises
- `2026-10-05-scenario-coverage-ratchets-one-directionally.md` — the ratchet this extends
