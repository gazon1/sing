---
title: "The adapter reads the contract, not the prose: four sgate adapters stop rebuilding a verdict their producer already emits"
date: 2026-10-20
status: accepted
tags: ["tooling", "ci", "gates"]
---

## Context

The inventory of the tooling (`docs/engineering/script-tooling-audit.md`, 2026-10-20)
found five `sgate` adapters that reconstructed a gate verdict by scanning their
producer's **human-readable output** with a regular expression.

Two of them (`build_output_readers`, `gradle_test_population`) had a producer that
already emitted the verdict as JSON, behind a `--json` flag the adapter never
passed. The adapter ran the producer in text mode and asked the output for the word
`OK`:

```python
_OK_RE = re.compile(r"check_build_output_readers:\s+OK\b")
...
"passed": bool(_OK_RE.search(stdout)),
```

The other three (`room_schema_integrity`, `test_task_external_inputs_declared`,
`light_adr_modules_stay_light`) had producers with no JSON branch at all.

Three further facts, all found while reading the same code:

- **`light_adr_modules_stay_light` pointed at a script that does not exist.**
  `scripts/check-adr-module-boundary.py` was deleted on 2026-10-15, in commit
  `a2d6cba8`, when the owner retired 26 gates in one pass — including "light ADR
  modules stay light". The adapter was classified as a `gate` in
  `_registry.COMMAND_MODULES` but appeared in neither `_registry.GATES` nor
  `scripts/ci/static-gates.sh`: it was in no suite, and it could only fail. Invoking
  it raised `NameError: name 'logger' is not defined` — the module used a logger it
  never imported — so even the "script not found" path was broken.
- **`--check` was accepted and ignored** in all five adapters. The help text promised
  "Dry-run: print result without exiting non-zero"; the flag did nothing.
- **`GatePayload.honest_pass`** — "pass only if the parser says so *and* the command
  exited 0" — was implemented in `scripts/src/sgate/models.py` and used by four
  modules. The five scraping adapters did not use it.

One more collision surfaced only under CI, and only after the contract existed:
**the GitHub Actions annotation is written to stdout**, because that is where
Actions reads workflow commands from. So a gate whose stdout carries a JSON
contract had two claimants for one channel — the contract and the annotation —
and in CI the annotation won, corrupting the document a consumer was about to
parse. `gate_fail` and `gate_warn` grew an `annotate` flag for this: a caller
whose stdout is a contract turns it off. The exit code is what fails the build;
the annotation is a convenience on top of it.

## Idea

A gate's verdict is a claim. A producer that knows its verdict can say it in a form a
machine can read; an adapter that rebuilds it from prose is parsing a human's
wording, and it will keep doing so until someone rewords an error message.

The fix is not "stop using regex" — it is "ask the producer for the answer, and make
asking possible where it is not yet". A gate that must remain a standalone process
gets JSON as an explicit output contract, and the adapter consumes that contract.

## Decision

**One helper reads the contract.** `run_json_contract` in
`scripts/src/sgate/commands/_runner.py` runs the producer with `--json`, parses its
stdout, and builds a typed `GatePayload`. A stdout that is not a JSON object is a
broken contract, not a passing gate: it is reported on **stderr** (stdout belongs to
the contract) and the gate exits `INTERNAL_ERROR` (5).

**Four adapters read it.** `build_output_readers`, `gradle_test_population`,
`room_schema_integrity` and `test_task_external_inputs_declared` no longer contain a
single verdict-shaped regular expression. Their exit code is the honest one: the
payload's `passed` ANDed with the producer's exit code.

**Two producers gain the contract.** `scripts/check-room-schema-integrity.py` and
`scripts/check-test-task-inputs.py` gain `--json`, emitting `passed`,
`blocking_errors`, and their own counters — the same shape the two producers that
already had it emit. This is the "make JSON an explicit output contract" step for the
gates that could not yet offer one.

**`--check` becomes true.** In all four adapters, `--check` now reports the verdict
and exits 0, in both the human and the JSON path. A flag that promises a dry run and
delivers a hard failure is the same defect class as a gate that checks less than it
claims: the caller is told something that is not true.

**The orphan is removed, not guarded.** `scripts/src/sgate/commands/light_adr_modules_stay_light.py`
is deleted, and its entry in `COMMAND_MODULES` goes with it. Guarding it would have
made a retired gate callable again, and its classification ("gate") was a claim about
a file that no longer existed — the same shape as `unwired_backlog_refs`, the first
such orphan, removed on 2026-10-19.

## Rationale

- **The producer already knows.** Re-deriving a verdict from a string is work that can
  only ever disagree with the thing that produced the string.
- **The contract is the smaller change.** Adding `--json` to two producers is a
  handful of branches; keeping five regexes in step with five output formats is a
  standing maintenance cost.
- **Removal beats repair for an orphan.** The owner retired this gate on 2026-10-15.
  Reviving its shim would resurrect a decision that was made against it.
- **`--check` was a promise, and the promise was false.** Fixing it here costs little
  and removes a lie a caller could act on.

## Consequences

- The four adapters contain no verdict-shaped regexes; the words `OK` and `ERROR` no
  longer decide whether a gate passes.
- A producer that changes its wording can no longer change a gate's verdict.
- A producer that stops speaking JSON fails the gate loudly (exit 5) instead of
  passing on an empty read.
- `light_adr_modules_stay_light` is no longer a command. The gate it wrapped was
  retired by the owner on 2026-10-15 and no longer ran anywhere; nothing loses a
  check.
- `scripts/tests/test_sgate_json_contract.py` pins the contract: the verdict is read
  rather than scraped, a failing producer fails, a producer that says it failed is
  never reported green, a broken contract is an internal error, and `--check` reports
  without failing.

## Links

- `docs/engineering/script-tooling-audit.md` — the inventory, finding F9.
- `scripts/src/sgate/commands/_runner.py` — `run_json_contract`, `run_gate_command`.
- `scripts/src/sgate/models.py` — `GatePayload`, `honest_pass`.
- ADR `2026-10-20-a-gate-that-reads-less-than-it-claims.md` — the round that found
  F9 and the shape the defects share.
- Commit `a2d6cba8` — the 2026-10-15 removal that retired the light-ADR gate and
  left its adapter behind.
