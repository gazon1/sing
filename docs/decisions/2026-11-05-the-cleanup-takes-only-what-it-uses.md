---
title: "The fixture cleanup takes only what it uses: the run id leaves the seam"
date: 2026-11-05
status: accepted
tags: ["e2e", "maestro", "tooling", "contracts"]
---

# The fixture cleanup takes only what it uses

## Context

`cleanup_run_fixtures` took four arguments: the device serial, a `run_id`, the
scope, and the `DeviceDeps` bundle. The `run_id` was the key of the retired
narrow scope, which swept the rows whose title started with it. That scope is
gone (`2026-10-28-the-bridge-policy-is-a-file.md`): the surviving wide scope
wipes the active profile's content and names no rows at all, and the `off`
scope sends nothing — so neither has a row the id could address. The parameter
survived only because the arity was asserted by
`scripts/tests/test_device_contracts.py` and every fake in the tree passed it,
and `_cli.py` filled it with a literal `""`.

## Idea

- Keep the four-argument shape, as the 2026-10-28 decision chose, on the
  grounds that removing it churns every call site for no behavioural gain.
- Remove the parameter, so the contract says what the cleanup actually uses.

## Decision

`cleanup_run_fixtures` takes the device serial, the scope and the `DeviceDeps`
bundle. The run id stays where it still means something: `run_one_flow` mints
one per attempt, declares it to the flow as `--env RUN_ID=<id>`, and logs it.

## Rationale

A seam that carries a value nothing reads teaches the next reader that the
value matters, and every new fake copies the extra argument because the
annotation has it. The churn the 2026-10-28 decision weighed is a handful of
mechanical edits, paid once; the confusion is paid on every read. The
`DeviceDeps` contract is still checked against `inspect.signature`, so the
bundle and the implementation cannot drift apart.

## Consequences

- The cleanup takes the device, the scope and the bundle — nothing else; a
  caller that finds itself with an id in hand is holding it for the runner, not
  for the cleanup.
- The run id is minted per attempt inside `run_one_flow`, handed to the flow as
  `--env RUN_ID=`, and logged; a test pins the mint, the declaration and the
  log line, so dropping one of them fails.
- Every `DeviceDeps` fake implements the cleanup with three parameters, and
  `scripts/tests/test_device_contracts.py` fails when a fake and the
  annotation disagree.

## Links

- Issue #1976
- `scripts/src/emulator/maestro/_fixtures.py`,
  `scripts/src/emulator/maestro/_types.py`,
  `scripts/src/emulator/maestro/_cli.py`
- `scripts/tests/test_run_maestro.py` (run id), and the cleanup scope tests
- Supersedes in part `2026-10-28-the-bridge-policy-is-a-file.md` and
  `2026-10-13-device-deps-di-pattern.md`
