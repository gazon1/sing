---
title: "An instrument nobody reads is not an instrument"
date: 2026-10-06
tags: [genui, observability, architecture, testing]
status: accepted
---

## Context

The GenUI layer grew two counters during the A2UI refactor: `GenuiRejectionCounter`, which tallies
contract violations, and `GenuiUsageCounter`, which tallies which component kinds a model actually
draws. Both are documented as instruments for one decision: *what should the catalog contain?*

The rejection counter is wired — `GenuiSession.respond` calls `recordAndReport` on every turn, and
the running distribution goes to the log beside the individual reasons.

The usage counter was wired halfway. `GenuiSession.reportUsage()` calls `report()` on a successful
turn, and `report()` logged the ranking: `task_card=7, text=12`. Meanwhile the function carrying the
counter's entire stated purpose — `neverUsed(catalog)`, whose KDoc says *"a component nobody reaches
for is either a prompt that failed to mention it or a feature that was never needed, and neither is
discoverable from the catalog's own declarations"* — had no production caller at all. It was
exercised only by its own unit tests.

So the instrument computed the answer to the question it existed to answer and then logged a
different one.

## Idea

1. **Leave it.** The ranking is the useful half; `neverUsed` is available to whoever asks in a
   debugger or writes a test.
2. **Log it from the counter.** `report()` prints the never-drawn list next to the ranking.
3. **Return it as data.** `report()` is a thin logger over a pure `summary(catalog)` that the
   session logs and a test asserts.

## Decision

Option 3, with the behaviour of option 2.

**The report has two halves, and both are emitted on the same turn.** The ranking says what is
popular. The never-drawn list says which of the other components the model was never shown how to
use. A log line containing only the first reads as though the question had been considered and found
settled, which is the specific harm: the decision gets made on `text=12` and the seventeen-component
catalog survives because nobody was ever shown the list of fifteen things it did not touch.

**`summary()` is pure and tested; `report()` is a logger over it.** A line written by a private
Kermit `logger` can only be asserted by capturing log output, and a test that does that asserts the
logging framework rather than the tally. Splitting the two makes the content testable in the same
place the counting already is, and keeps the log call trivially correct.

**An empty tally reports nothing.** A prose-only answer, or a failure before the first component,
yields no lines. "All seventeen components are unused" is what the counter says before any answer
exists, so repeating it per turn is noise rather than a finding.

## Rationale

The failure mode here is not a bug in any line of code — every function does what its own KDoc says.
It is a function that is correct, tested, and never called on the path that matters. That is the
shape this repository already has a detector for (`scripts/find-unwired-surfaces.py`), and it is the
reason the detector exists: the layer it protects against is not "nothing implements this", it is
"something implements this perfectly and nobody invokes it".

The generalisable rule, and the reason this is an ADR rather than a one-line fix: *an instrument has
to be connected to the decision it informs, not merely to the process that produces data.* A tally
that is recorded, held in a `StateFlow`, and exposed as a public property has acquired the
*appearance* of being used. The pull toward that appearance is strong precisely because every
individual step is defensible — the counter is useful, the property is reasonable, the test is
genuine, the function is correct. Nothing fails. The only thing missing is the last hop, and it is
the only hop that carries the answer to the person who would have acted on it.

A useful consequence of taking this seriously: when adding an instrument, the acceptance test
should assert what it *reports*, not that it *records*. `record("task_card")` proving the map has an
entry says nothing about whether anybody can act on the map.

## Consequences

- `GenuiUsageCounter.summary()` is public and tested; `neverUsed` gains the production caller it
  lacked, via `report()`.
- Three tests added: the report names unused components; an empty tally reports nothing; a drawn
  component leaves the unused list.
- `report()` takes an optional `catalog` parameter defaulting to `SingularityCatalog`, so a test can
  report against a small catalog rather than the seventeen-component one.

## Links

- Code: `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/core/GenuiUsageCounter.kt`
- Tests: `shared/src/commonTest/kotlin/com/singularity/todo/feature/genui/core/GenuiUsageCounterTest.kt`
- Related: `docs/decisions/2026-10-05-genui-catalog-as-contract.md`
- Detector: `scripts/find-unwired-surfaces.py`