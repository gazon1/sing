---
title: "The gate suite judges the modules the push touches"
date: 2026-11-08
status: accepted
tags: ["tooling", "gates", "pre-push", "tests"]
---

# The gate suite judges the modules the push touches

## Context

The pre-push hook runs the gate suite — the tests of the gate scripts
themselves — against the tree that is being pushed. It ran the whole suite:
`python3 "$tree/scripts/run-gate-tests.py"`.

The whole suite was red on `origin/main` on 2026-11-08: 3255 tests, 43
failures, in modules the branch had never touched (`test_toolchain_pins`,
`test_setup_local_tools_sh`, `test_run_maestro`, `test_refresh_decisions_digest`,
`test_pre_push_verdict`, `test_pre_commit_hook`). The step was the second wall
of the branch: the first was the formatting gate, which was narrowed on
2026-11-07 (`2026-11-07-the-formatting-gate-judges-the-sources-the-push-touches.md`),
and the second was the suite, which judged every module of it.

The suite was also red in the *test fixture* that drives the hook, and for a
different reason: the fixture had no `easement` on its `PATH`, so the hook's
easement step reported `FAILED` and every push in the fixture failed — a test
that depends on the machine it runs on is a test that measures the machine.
The same happened to the semaphore the detekt verdict runs under: a machine
that has a `heavy` shim which cannot resolve a version made every push in the
fixture fail with "mise ERROR No version is set for shim: heavy".

## Idea

Judge the suite the way the other partial gates are judged: the hook judges
the test modules the push touches, CI keeps judging the whole suite, and the
difference is written down where both can be read. And make the fixture
hermetic, so the hook's own tests say something about the hook.

## Decision

- **The scope is the test files under `scripts/tests/` the push carries; each
  one is a module of the suite, named the way the suite names its modules.**
  The set of files the push carries is the one the other `if-touched:` gates
  use. An empty set of carried files — a branch whose commits are already on
  some remote, which `git log --not --remotes` then drops — falls back to the
  history of the pushed commit: a gate that judges too much only costs time,
  while a gate that judges too little lets a defect through. The scope itself
  was completed on 2026-11-09: the carried files are read merge-aware and
  unescaped, the tree decides which of them the suite judges, and a changed
  helper reaches the modules that import it
  (`2026-11-09-the-push-scope-is-what-the-tree-carries.md`).
- **A push that touches no test of the suite skips the step**, and says so:
  `gate suite tests: SKIPPED (the push touches no test of the gate suite)`.
  Nothing of the suite can be broken by what such a push carries.
- **The scope travels as `--module NAME` on the runner.** The runner is the
  copy in the tree being pushed (`script_from="tree"`), and a branch cut
  before this change does not know the flag; a tree without the runner falls
  back to plain `unittest` over the same modules. The flag is a list of
  modules, not positional arguments, because `unittest` takes neither a bare
  module name nor a path.
- **The whole suite stays the default of `scripts/run-gate-tests.py`.** The
  runner's report is about the whole suite, and CI is the place that judges it.
- **The record says what each side judges.** `config/hooks/pre-push.json` gains
  a `gate_suite` section, and `scripts/check-hook-gates.py` checks the claim
  against the step that runs: the step has to name the scope it passes and the
  skip it prints.
- **The hook's own steps stay strict; the fixture grows stand-ins for the
  tools.** "Easement is not installed" is a failure, and it is not made softer
  for the sake of a test. Instead the fixture installs a stand-in for the
  *tool* (`easement` on the `PATH`, writing down the calls it received) — the
  step still runs, still fails when the tool fails, and the test no longer
  measures the machine it runs on. The same goes for the semaphore the detekt
  verdict runs under: the hook falls back to running the verdict directly when
  the semaphore is absent, but a machine that has a shim which cannot resolve
  a version fails the verdict for a reason that has nothing to do with the
  code. The stand-in for it does what the hook needs of it — it runs the
  command after the separator.

## Rationale

- **A local gate that judges the whole tree reports other people's defects.**
  The same argument as the formatting gate: the author of a change cannot fix
  a failure in a module the change does not carry, and learns to reach for
  `--no-verify`.
- **The scope is the change, not the tree.** The suite's price now follows the
  change: two touched modules cost two modules, a push that touches no test
  costs nothing.
- **A failure in a module the push does not carry is still found.** CI runs the
  whole suite in the `Gate tooling` job. The narrowing moves the finding, it
  does not drop it.
- **The narrowing is not a silencing.** The step prints the modules it judged
  (`gate suite tests: OK (test_pre_push_hook, test_check_hook_gates)`), the
  record says what each side judges, and the gate checks the record against the
  code.
- **A test that depends on the machine is a test that measures the machine.**
  The fixture's missing `easement` had nothing to do with the code under test,
  and it turned ~20 hook tests red on every machine that did not have the tool
  on the `PATH`.

## Consequences

- A push that touches no test of the suite skips the step; a push that touches
  two is judged by those two.
- The failures on `origin/main` no longer block a push of a branch that does
  not touch them. They still make the CI `Gate tooling` job red — CI judges
  the whole suite, and that is its job.
- The suite's own tests say something about the hook on any machine: the
  fixture installs the stand-in itself.
- `scripts/check-hook-gates.py` fails when the record does not state the
  partiality of the gate suite, when the step does not pass a scope, and when
  the step has no skip.

## Links

- ADR `docs/decisions/2026-11-09-the-push-scope-is-what-the-tree-carries.md`
  — the scope of the push: merge-aware, unescaped, and decided by the tree.
- ADR `docs/decisions/2026-11-07-the-formatting-gate-judges-the-sources-the-push-touches.md`
  — the same principle applied to the formatting gate.
- ADR `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`
  — the local verdict judges what the change reaches.
- `.githooks/pre-push` — the hook, and the place the module list is built.
- `scripts/run-gate-tests.py` — the runner, and the `--module` flag.
- `config/hooks/pre-push.json` — the record of what the hook judges and what
  only CI judges.
- `scripts/check-hook-gates.py` — the gate that keeps the record and the code
  together.
