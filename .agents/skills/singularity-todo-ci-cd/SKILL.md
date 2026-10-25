---
name: singularity-todo-ci-cd
description: How the gate suite, the CI workflows and the review verdicts fit together in the Singularity Todo repository. Use when adding or changing a gate, when a generated gate file has to be regenerated, when waiting for CI to finish, when the gate tooling is suspected of measuring another checkout, or before opening a pull request. Covers the gate registry as the single source, the generated surfaces, the measurement gates and their floors, and where a review verdict is recorded.
---

# CI, the gate registry and the review verdicts

## The registry is the only list of gates

`scripts/src/sgate/commands/_registry.py` plus
`scripts/src/sgate/gate_manifest.py` hold every gate. `scripts/ci/static-gates.sh`
and `docs/scripts/gate-registry.md` are **generated** from it — do not edit
them by hand:

```bash
PYTHONPATH=scripts/src python3 -m sgate render-static-gates --write
python3 -m sgate render-registry
```

The `static` job in `ci.yml` and `check.sh` take the same registry: a gate
cannot live on one surface and be missing from the other. A new gate is one
entry in the registry plus one `gate blocking|advisory <name> <cmd>` line;
`|| true` on a `gate` line is forbidden. Branch protection requires **one**
check — `CI gate`. The composition of the workflows, the parts A–G of the
meta-gate and the recipes are in the skill `singularity-todo-quality-tools`.

## Waiting for CI — one command, not a poll loop

```bash
gh pr checks <pr> --watch --interval 60   # waits to the end, prints the verdict
```

Ten `sleep 30 && gh pr checks` is ten rounds of context spent on nothing, and
it is exactly what the "a long run is one background task" rule forbids.
`--watch` waits by itself and hands over the final verdict in one line.

## The gate tooling is installed, not put on `PYTHONPATH`

On a machine with several clones, the `sgate` on `PATH` is an editable
install of *another* clone, and the gate then checks that clone's code while
calling it its own. Install it once per clone:

```bash
bash scripts/ci/setup-local-tools.sh          # uv venv + editable install of scripts/src
export PATH="$HOME/.venvs/singularity-todo-$(basename "$PWD")/bin:$PATH"
sgate whereami                                 # the path must match this clone
```

`bash scripts/ci/preflight-gate-tools.sh` checks this and says what to fix. A
manual `PYTHONPATH` inside a script is not a path: the gate unit tests run
the scripts with a minimal environment (`env={'PATH': ...}`), and a package
visible only through `PYTHONPATH` is invisible to them. Our `sgate run-all`
runs this clone's gates itself (`run_all.py` → `_repo_env()`), so a foreign
install on `PATH` does not spoil the gate results — but calling `sgate` by
hand then is not allowed.

## The job graph — where each verdict is produced

`ci.yml` carries five jobs that produce verdicts, and each one answers a
different question:

| job | what it answers | when it runs |
|---|---|---|
| `static` | do the gates pass, and can they still fail? | a change to the gates, or on the trunk |
| `detekt` | is the code clean? | wherever the suite runs |
| `quick` | do the tests THIS change can break pass? | a pull request |
| `coverage` | is the coverage floor met? | the trunk, the schedule, a pull request labelled `run-full-tests` |
| `tests` | is the suite green? | wherever app code changed |

Three facts about them are not obvious from the file:

- **The pull-request path runs no coverage measurement.** The Kover agent is
  attached by `enableCoverage()` in `settings.gradle.kts`, and the
  pull-request path passes `-Pcoverage.enabled=false`; the coverage job passes
  `-Pcoverage.enabled=true`. The agent is a property of the CONFIGURATION, so
  dropping a coverage task from an invocation does not drop the cost.
- **The control phase is conditional.** The harness that proves each gate can
  fail runs when the gate corpus changed (`scripts/**`, `config/**`,
  `.github/**`) or on the schedule — the `changes` job computes that and the
  `static` job reads the answer. A change to the app cannot make a gate stop
  working.
- **The declaration order is part of the contract.** The registry's controls
  resolve their targets by the FIRST match in the file, so `coverage` is
  declared BEFORE `tests`, and a control's anchor names the step its sabotage
  lands in. `scripts/tests/test_sgate_controls.py::TestTheControlThatMustBeAbleToFail::test_every_control_still_hits_the_line_it_claims_to_prove`
  is the gate for that.

The decision is ADR
`2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md`.

## The gates are measurements, not booleans

All of them are blocking. The spec is
`openspec/specs/test-execution-integrity/spec.md`, the decision is ADR
`2026-10-04-measurement-integrity`:

- `check-test-runs.py --require <set>` — ran fewer classes/tests than the
  floor, **or** skipped any;
- `:koverVerify` — the INSTRUCTION/BRANCH/LINE coverage of
  `com.singularity.todo.*` is below the floor 27/20/27;
- `check-flaky-tests.py --current DIR --previous DIR`;
- `unittest discover -s scripts/tests` — a regression in the gates themselves
  (~3 min on 1750 tests).

The floor is the **smallest** legitimate run: a failure is to be
investigated, not regenerated.

## Before a PR — a review by a separate agent

The `code-review-pr-workflow` skill: it gets the diff, not the code, and it
has no pressure of implementation context — so it catches what the gate does
not (the state contract, an alias, an entry in the issues registry). The gate
catches the mechanics; the review catches the meaning.

## The review verdict is a file, not a recollection

Every change that is not in the baseline has `.specify/reviews/<slug>.md`:
the change, the verdict (`pass`/`fail`), the date, the PR, and what the
review found. The file name is the **slug without the date prefix**: for the
directory `2026-10-22-the-read-surface` the verdict lives in a file named
`the-read-surface`.

The gate `review verdicts cover every change` requires it. The changes that
predate the rule are in `config/docs/review-verdicts-baseline.txt`, and that
list only shrinks: `--update-baseline` prunes it but never adds to it, and
adding an entry is a manual edit that shows up in the diff. A scaffold that
spec-kit has just created is exempt from the rule until somebody writes the
verdict.
