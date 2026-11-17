---
title: "The failure message is normalised into the registry's wording"
date: 2026-11-17
status: accepted
tags: [testing, maestro, android, tooling]
---

# The failure message is normalised into the registry's wording

## Context

The cause registry (`config/docs/maestro-debt.yaml`) is keyed on the failure
message the Maestro CLI prints: `Assertion is false: id: x is visible`. The
gate reads a report, takes the first line of the failure message, and looks
that string up in the registry. A cause the registry does not list is a
finding — the debt changed kind, and a count of failing flows cannot see
that.

maestro-runner describes the same failure in its own words: `Element '#x' not
visible within 8s (cause: timed out)`. Under the runner, every cause read as
new. The report's *shape* was already rewritten into the shape the gates read
(`normalise_junit` puts the flow's path into the `file` attribute the CLI
uses); its *message* was not, so a runner run produced 24 findings of one
kind on a run whose failure count was exactly at the floor.

Two of the runner's wordings name no element at all: `Element not found:
handler_error: Element not found in any of 2 strategies` and `Element not
found after 20 scrolls`. The CLI names the element in both cases
(`Element not found: Id matching regex: x`, `No visible element found: id:
x`), so the runner's message alone cannot be keyed.

## Idea

The registry describes causes, not sentences. The two engines' sentences
differ; the causes do not. Since the report is already rewritten into the
shape the gates read, its wording can be rewritten too — at the same point,
in the same place — so that one registry describes a run under either engine,
and the registry itself stays as it is.

The element the runner's message does not name is not lost: maestro-runner
writes the whole run's tree next to the report, and the command that failed
is in it with the selector it asked for.

## Decision

**The failure message is normalised into the CLI's wording, at the point the
report's shape is normalised.** `normalise_junit` rewrites the runner's
message where the two engines mean the same thing:

| runner | CLI |
|---|---|
| `Element '#x' not visible within 8s (cause: timed out)` | `Assertion is false: id: x is visible` |
| `Element not visible: element '#x' not found (cause: …)` | `Assertion is false: id: x is visible` |
| `Element '#x' still visible after 5s (cause: timed out)` | `Assertion is false: id: x is not visible` |
| `Element not found: handler_error: …` | `Element not found: Id matching regex: x` |
| `Element not found after 20 scrolls (cause: element not found)` | `No visible element found: id: x` |

**The element the runner's message does not name comes from the runner's own
report tree.** The tree holds the command that failed and the selector it
asked for; the run's own id is put back into a `${RUN_ID}` placeholder, so
the element is named the way the CLI named it.

**A message that names no element and has no tree to say it is left alone.**
A cause that does not say what it is about is a cause nobody can classify, and
inventing one would be a lie. The rewrite is what it is: the same cause, said
the way the registry says it.

**The registry is not rewritten.** It stays keyed on the CLI's wording, and
the report is made to say what the registry says.

## Rationale

Measured 2026-11-17 on the whole suite under both engines, on the phone that
carried the branch build (realme RMX3938, Android 15), 127 flows of the
`full` scope:

| | the CLI's run | the runner's run, before | the runner's run, after |
|---|---|---|---|
| flows reported | 127/127 | 127/127 | 127/127 |
| failures | 33 | 32 | 30 |
| causes | 29 | 25 | 29 |
| findings | 22 | 26 | 22 |
| of which "a cause the registry does not list" | 19 | 24 | 18 |

After the rewrite the runner's run produces the same *shape* of findings as
the CLI's: the same kinds, the same count, and the same environment. The
remaining difference — one cause the CLI's run did not have — is a flow the
runner failed on and the CLI did not, which is the engine difference the 2×2
of the earlier decision already measured.

Before the rewrite every cause the runner produced was new, and the debt's
kind was invisible: a run that failed on the same elements as the last one
read as 24 new causes.

The remaining runner-only causes are not wording:
`Assertion is false: id: auth_save_server_button is visible` (the CLI passed
those flows), `Assertion is false: id: snackbar_action is visible`,
`Element not found: Id matching regex: dialog_confirm`, and the two the
runner names a different element for. Those are the engine's verdicts, not
its sentences.

## Consequences

- The registry describes a run under either engine, and the registry is
  unchanged: no cause was added to it, and none was removed.
- The runner's report tree is part of the contract now. A report whose tree
  is missing falls back to the message alone, and a message that names no
  element then stays unclassified — visible, not hidden.
- A flow the runner fails on and the CLI does not is a new cause under the
  runner, and it says so. That is the engine's verdict, and the gate is
  right to refuse it until somebody accepts it.
- The rewrite is engine-agnostic in the sense that matters: it is applied to
  every report, and a CLI report does not match any of the runner's shapes,
  so it is left exactly as the CLI wrote it.
- The runner is still an experimental path, and this decision does not make it
  one: it is not installed through mise, CI does not run it, and the rewrite
  covers the wordings measured here rather than every message the runner can
  produce. The CLI stays the default until the runner has a CI check of its
  own.

## Links

- `docs/decisions/2026-11-17-maestro-runner-is-a-second-engine.md` — the
  second engine, and the finding this decision answers
- `scripts/src/emulator/maestro/_engine.py` — the report normalisation
- `scripts/ci/check-maestro-results.py` — the gate that reads the report
- `scripts/tests/fixtures/maestro-junit/` — the same failure, said by each
  engine
