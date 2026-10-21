---
title: "One owner for stdout: the JSON contract becomes a mode, and a gate that keeps every gate to it"
date: 2026-10-21
status: accepted
tags: ["tooling", "ci", "gates"]
---

## Context

The previous round (ADR `2026-10-20-f9-adapters-read-the-contract.md`) gave the
gate adapters a JSON output contract: one JSON object on stdout, carrying the
verdict. It also left three things unfinished.

**Two places still broke the contract.** The GitHub Actions annotation
(`::error::`) is written to **stdout**, because that is where Actions reads
workflow commands from. So a gate whose stdout carried a document had two
claimants for one channel, and in CI the annotation won — the document a
consumer was about to parse was preceded by a line that is not JSON. The
round-2 fix covered the four adapters it had rewritten, but not:

- `handle_result` (`scripts/src/sgate/commands/_runner.py:299`), which is what
  **every registry-generated gate** calls in `--json` mode
  (`scripts/src/sgate/commands/_registry.py:1043`);
- a hand-copied `_handle_result` in `scripts/src/sgate/commands/adr_status_policy.py:22`,
  which was the same function with the same four lines in a different order.

**The opt-out was a per-call flag.** The round-2 fix added `annotate=False` to
`gate_fail` and `gate_warn`. A flag cannot express "stdout has exactly one
owner": a new adapter that forgets it repeats the defect, and nothing in the
code says which one owns stdout.

**Nothing checked the contract.** The class of defect — a document that is not
a document — had already happened twice (the annotation collision, and the four
adapters that rebuilt a verdict from prose), and both times it was found by
reading the code, late. A gate that reads what every other gate reads would
have found both.

Writing the new gate found a third instance immediately: `sgate render-registry
--check --json` printed `{"status": "current"}` — a status word, and no
verdict. A consumer would have had to infer the pass from it, which is the
scraping this contract exists to end.

## Idea

A gate's stdout has one owner. The contract should be **declared once, where
the contract is emitted**, and checked by a gate that reads every other gate
the way `sgate run-all` does.

## Decision

**One mechanism: a mode, not a flag.** `contract_stdout()` in
`scripts/src/sgate/output.py` is a context manager that claims stdout for the
contract. Inside it, the GitHub Actions annotation goes to stderr instead of
stdout: it stays visible in the CI log, it simply stops being a workflow
command, which is the right trade when stdout belongs to a document. The
exit code is what fails the build either way. The `annotate` flag is gone —
two mechanisms for one rule is how they drift.

The mode is thread-local: a gate runner may execute gates in parallel, and a
contract belongs to one gate's stdout, not to the process.

**The two remaining holes are closed.** `handle_result` now builds a
`CapturedPayload` — the typed shape that already existed in
`scripts/src/sgate/models.py` for exactly this case, and that nothing used —
and emits it inside the mode. `adr_status_policy`'s hand-copied handler is
deleted; the shared one does the job. Its `--check` flag, which was accepted
and ignored like the four adapters' was, now reports and exits 0.

**Every gate's document says whether it passed.** `render-registry --json`
carries `passed` alongside its status. A document that does not say is not a
verdict, and a consumer that has to infer it from a status word is back to
parsing prose.

**A gate keeps every gate to the contract.** `scripts/check-gate-json-contract.py`
runs each gate the shell registry dispatches through `sgate` with `--json` and
requires stdout to be exactly one JSON object carrying `passed`. It reuses the
liveness probe's heavyweight filter, so the two gates skip the same gates: a
gate that boots Gradle is expensive for both, and two independent filters
would drift apart exactly where the cost is.

The probe runs the module from **this** checkout (`python3 -m sgate` with
`PYTHONPATH` pointing at `scripts/src`), not the `sgate` on PATH: on a machine
with two checkouts of this repository the binary on PATH is an editable
install pointing at the other one, and the gate would certify somebody else's
code.

## Rationale

- **A mode is a fact; a flag is a promise.** The mode says "stdout is
  mine" where the contract is emitted. A flag asks every future caller to
  remember.
- **The gate reads what the runner reads.** `sgate run-all` calls gates with
  `--json`; the new gate calls them the same way. A checker that used a
  different invocation would certify a path nobody takes.
- **The heavy filter is shared, not reimplemented.** Two filters for the same
  question are two answers to it.
- **A document that does not say is not a verdict.** Otherwise the contract is
  a convention, and conventions are what the last two rounds were about.

## Consequences

- Every gate dispatched through `sgate` prints one JSON object on stdout in
  `--json` mode, and the new gate says so on every run.
- The GitHub annotation of a failing gate is no longer a workflow command when
  the gate is emitting a document. It is still in the log, and the exit code
  still fails the build.
- `adr_status_policy` lost a copy of `handle_result` and gained a working
  `--check`.
- The new gate costs one pass over the gate corpus (about 25 s here) on top of
  the liveness probe's pass.
- The gate's control sabotages the shared runner rather than one adapter: the
  defect it reproduces is not an adapter's, it is a second writer on the
  contract channel.

## Links

- ADR `2026-10-20-f9-adapters-read-the-contract.md` — the round that introduced
  the contract and left these three holes.
- ADR `2026-10-20-a-gate-that-reads-less-than-it-claims.md` — the shape the
  defects share: a claim about a corpus narrower than the corpus.
- `scripts/src/sgate/output.py` — `contract_stdout`.
- `scripts/src/sgate/commands/_runner.py` — `run_json_contract`, `handle_result`.
- `scripts/check-gate-json-contract.py` — the gate.
- `scripts/tests/test_check_gate_json_contract.py`, `scripts/tests/test_sgate_json_contract.py`.
- Issues #769, #770, #771.
