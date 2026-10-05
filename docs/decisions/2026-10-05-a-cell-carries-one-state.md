---
title: A cell carries one state, because four booleans could say what the matrix cannot render
date: 2026-10-05
status: accepted
tags: [traceability, tcm, spec, gates, refactoring]
---

## Context

`CoverageCell` accumulated one boolean per fact: `claimed`, `automated`,
`deprecated`, then `reachable`. Four independent inputs describing five states.

That shape had already produced one bug this year, and it was the shape, not the
rule: the `deprecated` case could not be expressed, so the renderer inferred it
and guessed wrong. `2026-10-05-a-hole-says-which-supply-it-needs` then added a
third boolean beside the two and recorded the trap in advance — *"the next
addition should be a small enum, not a fourth boolean"* — on the grounds that
changing the shape under three call sites during an unrelated change is its own
risk.

`◇` is the addition that made the prediction concrete: five states over four
booleans, and combinations that no rendering describes. `claimed=False,
automated=True` is the plain one, and it was constructible. The glyph cascade
fell through to the unclaimed dash, so the impossible cell was **indistinguishable
from an ordinary one** — the same failure mode as the `deprecated` bug, waiting
for the next caller to hit it.

## Idea

Keep the facts, but consume them exactly once, in one function with a written
precedence, and store the answer. The booleans are the *inputs* to a decision,
not the shape of the thing.

## Decision

`CellState` — `UNCLAIMED`, `RETIRED`, `AUTOMATED`, `HOLE`, `UNREACHABLE` — and one
`classify(*, claimed, automated, deprecated, reachable)` whose precedence is the
decision. `CoverageCell` holds `state` and nothing else, so an unsatisfiable
combination is not discouraged, it is unrepresentable.

The precedence, in the order `classify` applies:

1. **Retirement outranks everything.** A retired scenario that still had a carrier
   keeps showing which platforms it covered, but renders `⊘`. Under the booleans
   `automated and deprecated` was reachable and drew `●` — a scenario retired
   after it was automated would have stayed in the matrix forever, looking
   supplied.
2. **An unclaimed target is never automated and never a hole.** The claim is the
   whole question; the remaining two facts cannot manufacture one.
3. **Reachability only distinguishes two kinds of hole.** Both remain holes and
   both are counted; only the glyph and the `unreachable_cells` metric differ.

Two predicates do the work the booleans used to do, and the split between them
is load-bearing: `is_claimed` keeps a retired row in the result matrix (it records
which targets the scenario used to cover), `is_obligation` drops it from the
rendered denominator. Collapsing them would reintroduce the
`0/2 claimed cells automated · 0 holes` contradiction `render.py` already has a
comment about.

## Rationale

The change is behavioural-neutral by measurement, not by argument: the ratchet
still reads **32 holes, 16 dark scenarios, 6 unreachable cells, 3 carriers across
19 specs**, and `traceability coverage --check` reports the committed matrix
unchanged, byte for byte. A shape change that moves no number is safe to merge;
one that does needs an argument.

Three call sites read booleans and all three now read states. The four facts are
built in exactly one place — `build_coverage` — which is where the spec decides
them.

The alternative considered and rejected was keeping the booleans and adding a
`validate()` that rejected impossible combinations. It is more code, it runs
after the mistake rather than preventing it, and every future field re-opens the
question.

## Consequences

`CoverageCell(claimed=..., automated=...)` is now a `TypeError`, and a test says
so. The nine tests in `CellStatePrecedence` exist to pin the precedence, not the
rendering: the rendering was already right for every state the old booleans could
legitimately produce, and it is the *illegitimate* ones that were silent.

The cost is verbosity at call sites — `cell.state.is_automated` reads longer than
`cell.automated`. That is the price of not having a boolean mean two different
things depending on which of the others it is combined with, and there were three
of them.

`SpecStatus` on the spec is still a separate enum from `CellState` on the cell,
and the two overlap deliberately: a deprecated *spec* produces a deprecated *cell*
on every target it used to claim.

## Links

- `infra/kiwi/traceability/coverage.py` — `CellState`, `classify`, `CoverageCell`
- `scripts/tests/test_traceability.py` — `CellStatePrecedence`
- `2026-10-05-a-hole-says-which-supply-it-needs.md` — the `◇` state, and the prediction this fulfills
- `2026-10-05-one-task-detail-screen-after-a-merge-split-the-nav-graphs.md` — the `deprecated` bug of the same shape