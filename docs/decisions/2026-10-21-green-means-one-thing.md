---
title: "Green means one thing: the runner reads the gate's verdict, not only its exit code"
date: 2026-10-21
status: accepted
tags: [tooling, gates]
---

# Green means one thing: the runner reads the gate's verdict, not only its exit code

## Context

A gate result had three shapes:

- `GatePayload` (`gate`, `passed`, `blocking_errors`, `warnings`) — the typed
  payload six modules emit;
- the `handle_result` dict (`gate`, `passed`, `exit_code`, `output`);
- `GateResult` in `scripts/src/sgate/commands/run_all.py` (`name`, `mode`,
  `passed`, `exit_code`, `duration_ms`, `stdout`, `stderr`).

And the runner computed its verdict from the **exit code alone**:

```python
passed = rc == CLEAN
```

So "green" meant one thing inside the gate — the payload's `passed` — and
another thing in the runner — the process's exit status. The rule that ties the
two together already existed and was already written down:
`GatePayload.honest_pass` — *pass only if the parser says so AND the command
exited 0* — but the runner did not use it.

Nothing detected the difference. A gate that emitted `passed: false` and
exited 0 was green in the report, and the report is what a human reads.

## Idea

**The gate knows the answer; the runner should read it.**

The runner already asks every `sgate`-dispatched gate for `--json`, so the
verdict is *there* — in the document on stdout, in the channel the contract
owns. The runner was simply not reading it.

## Decision

**`run-all` takes the verdict from the gate's payload, ANDed with the exit
code.**

- `_read_gate_payload(stdout, gate)` returns the gate's own verdict when stdout
  is the contract — one JSON object with a boolean `passed` — and `None` when
  it is not.
- The verdict is `payload.honest_pass(rc)` when there is a payload, and
  `rc == CLEAN` when there is not. One rule, from one place:
  `GatePayload.honest_pass`.
- Only the verdict is taken from the document. The rest of the payload is the
  gate's business, and a document whose extra fields do not fit the payload is
  still a document that said `passed`.
- A gate run as a plain script prints human text, so there is no payload to
  read and the exit code is the only verdict there is. The contract gate's job
  is to say when a stdout is not the contract; the runner does not guess a
  verdict out of prose.
- The report names the case: a gate whose payload says it failed and which
  exited 0 is printed as `✗ payload`, not `✗ exit=0`. A row reading "exit=0"
  on a failing gate reads like a bug in the table.

## Rationale

- **One meaning for green.** The report's `passed` comes from one computation
  instead of two independent ones, so the runner and the gate cannot disagree
  about whether a gate passed.
- **A red gate must not be able to hide behind a zero.** The failure direction
  this closes is the quiet one: the payload says red, the exit code says zero,
  and the report says green.
- **The rule was already written.** `honest_pass` was the repository's answer
  to this question; the runner simply did not ask it. Using the rule that
  exists beats writing a second one.

## Consequences

- A gate that reports `passed: false` with exit code 0 is reported as **failed**
  by `sgate run-all`, with the reason in the report and in the failure output.
- `GateResult` carries `payload_passed`, so the report can say *why* a gate is
  red instead of only its exit code.
- The six tests that pin this live in `scripts/tests/test_sgate_gates.py`: a
  payload that says red with exit 0, a payload that says green with exit 0, a
  payload that says green with a non-zero exit, a gate with no payload at all,
  a stdout that is not JSON, and a document with no verdict in it.

## Links

- `scripts/src/sgate/models.py` — `GatePayload.honest_pass`, the rule.
- `scripts/src/sgate/commands/run_all.py` — the runner that now asks it.
- `2026-10-21-one-owner-of-stdout` — the round that gave stdout a single owner,
  which is what makes the document on it readable.
