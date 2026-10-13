# Engineering SDD

A Spec Kit workflow for **internal engineering changes** — the ones no user sees, but which still
need formalized behavior, state modeling, concurrency analysis, diagnosis and provable correctness.

State machines, coroutine lifecycle, sync/queue correctness, cancellation, flaky tests, deadlocks,
diagnostics, and internal refactors whose behavior must be preserved.

**Use `openspec-change` instead** for anything a user can observe: new features, UI, user-facing
behavior, capability contracts. The two pipelines are deliberately separate, not two settings of one
pipeline — the questions they answer are different.

---

## Quick start

```bash
specify workflow info engineering-sdd      # validate and print the step graph — runs nothing

specify workflow run engineering-sdd \
  --input slug=fix-countdown-reentrancy \
  --input title="CountdownStateMachine re-entrancy" \
  --input request="The countdown silently cancels when the undo slot advances" \
  --input depth=deep

specify workflow status <run-id>           # after a gate pauses a non-TTY run
specify workflow resume <run-id>
```

### Inputs

| Input | Required | Meaning |
|---|---|---|
| `slug` | yes | kebab-case change id. Becomes `openspec/changes/<slug>/`. |
| `title` | yes | short human title |
| `request` | yes | the engineering problem or capability, in your words |
| `depth` | no (`standard`) | `quick` \| `standard` \| `deep`. **Documentation depth, never correctness.** |
| `implement` | no (`true`) | `false` stops after the plan — no code is written |
| `run_tests` | no (`true`) | `false` runs no tests and records every check `NOT_RUN` |

`slug` must match `^[a-z0-9]+(-[a-z0-9]+)*$`. It becomes a path segment that later steps open, so
the first step refuses anything else rather than creating something unsafe.

---

## The steps

```
explore → specify → model → verify-design → [GATE review-spec]
        → plan → [GATE review-plan]
        → implement → verify → converge        (only when implement=true)
        → assert-artifacts → next-steps → git-status
```

| # | Step | Skill | Writes |
|---|---|---|---|
| 1 | explore | `speckit-engineering-explore` | `.openspec.yaml`, `investigation.md` |
| 2 | specify | `speckit-engineering-specify` | `spec.md` |
| 3 | model | `speckit-engineering-model` | `model.md` |
| 4 | verify-design | `speckit-engineering-verify-design` | `verification.md` |
| 5 | **review-spec** | gate | — |
| 6 | plan | `speckit-engineering-plan` | `design.md`, `tasks.md` |
| 7 | **review-plan** | gate | — |
| 8 | implementation | `if` on `implement` | — |
| 9 | implement | `speckit-engineering-implement` | application code |
| 10 | verify | `speckit-engineering-verify` | `results.md` |
| 11 | converge | `speckit-engineering-converge` | appends `## Divergences` |
| 12 | assert-artifacts | shell | — |
| 13 | next-steps | shell | — |
| 14 | git-status | shell | — |

Each step is a **separate `mcode exec` subprocess with no memory of the previous one**. The skills
are therefore written to be self-sufficient: each names its input files, its output file, and its
forbidden actions. Read `.specify/templates/engineering/conventions.md` — every skill starts there.

### The two gates

**review-spec** — you are confirming *what must be true*. Problem and scope, invariants, required
behavior, the state model, the verification strategy, and the unknowns that block all of them.

**review-plan** — you are confirming *how to build it*. Solution, alternatives, affected modules,
compatibility and migration, and the task breakdown.

Both run before any code is written, and a rejection aborts the run.

**Gates are process control, not a security boundary.** Each agent step runs with
`--permission full`; "do not modify code before the gate" is an instruction, not an enforced
constraint. A real guarantee would need a restricted-permission mode or an isolated checkout.

---

## Artifacts

All in `openspec/changes/<slug>/`:

```
.openspec.yaml      skip_specs, or a capability delta if the contract changed
investigation.md    verified facts, hypotheses, reproduction, unknowns
spec.md             required behavior, INV-NN invariants, AC-NN criteria
model.md            states and transitions — or a justified "Not applicable"
verification.md     how correctness will be proven
design.md           the chosen solution and its alternatives
tasks.md            executable work, each bound to an AC-NN
results.md          what actually ran + divergences
specs/<cap>/spec.md only when an observable capability contract changes
```

Templates for each live in `.specify/templates/engineering/`.

### Acceptance criteria bind to named tests

```
- [ ] AC-01: When <precondition> and <event>, the system must <observable result>.
      `Verification:` `<TestClass>::<testFunction>` — `./gradlew :shared:jvmTest --tests '<Class>'`
```

The test does not exist when the spec is written — `verify-design` designs it, `implement` writes
it. Naming a *resolvable* test id is what makes the binding checkable later instead of aspirational.
It is also a real gap versus the mainstream: no standard format binds an acceptance criterion to a
runnable test, and spec-kit's own traceability is editorial rather than enforced.

### The transition table is a test design

`model.md`'s columns are fixed, and deliberately mirror jqwik's state-machine model:

| `model.md` column | jqwik concept |
|---|---|
| Event / trigger | `Action` |
| Guard | `Action` precondition (a generation constraint) |
| State mutation | `Transformer.mutate()` + postcondition |
| Invariants checked | `withInvariant(...)` |
| Counterexamples | shrunk to a minimal failing chain |

That makes a later move to property-based testing mechanical. Two things to know before you take it:

- **jqwik gives no virtual time.** Its coroutine support uses real delays. For coroutine pipelines
  keep using `runTest` + `advanceUntilIdle()`; jqwik suits pure state logic.
- **Pin jqwik 1.9.3, not 1.10.** Version 1.10 added an "Anti-AI Usage Clause" discouraging use with
  coding agents, and 1.10.0 was withdrawn. 1.9.3 avoids both.

PITest, run on changed lines only, answers the complementary question — whether a test that exists
can actually fail. It is the honest check on "tests prove correctness".

---

## Trust boundary: never interpolate inputs into a `run:` block

**This is the one rule that matters if you edit this workflow.**

Spec Kit expands `{{ ... }}` in a `shell` step and then executes the result with `shell=True`.
Any user-supplied text inside a `run:` block **is executed shell**.

```yaml
# WRONG — `slug` becomes part of a shell program
run: |
  DIR="openspec/changes/{{ inputs.slug }}"   # a slug of $(id) executes
```

What this workflow does instead:

- `slug`, `title` and `request` are interpolated **only** into `command:` step `args`, which become
  a single argv element — no shell.
- `depth` is an `enum`, so it is a closed, startup-validated set.
- `assert-artifacts` takes **no interpolation at all**. It discovers the change directory from the
  newest `investigation.md`, re-validates the slug against `^[a-z0-9]+(-[a-z0-9]+)*$` before using
  it in a path, and fails loudly if more than one candidate exists.

Also note: a `condition:` written without `{{ }}` is silently **always-true**. `specify` does not
evaluate a bare expression. `condition_is_never_evaluated()` exists to reject exactly that.

The existing `openspec-change`, `adr-create` and `adr-batch` workflows **do** interpolate free text
into `run:` blocks — including unquoted heredocs — and `adr-gate.yml` passes an unsanitized pull
request title into one of them. Fixing those is tracked separately; this workflow is immune by
construction.

---

## Two outcomes

| Outcome | Condition | Produces | Issue status (printed, never written) |
|---|---|---|---|
| `plan-only` | `implement=false` | investigation, spec, model, verification, design, tasks | `open` |
| `implemented` | `implement=true` and steps 9–11 ran | the above **plus** `results.md` | `accepted`, only after real verification |

A plan-only run never claims implementation and never asserts `accepted`.

**Issue-registry registration is manual in 0.1.0, on purpose.** `check-speckit-issue-registry.py`
requires `version`, `type`, `slug`, `status` and an existing `openspec_path`, and the corpus is
already 577/633 failing. The final step prints the template and the validation command rather than
writing a record nothing validates.

---

## Running concurrently

Not re-entrant on one slug: two runs sharing a slug race on artifact writes. A slug is claimed by
one run at a time. For parallel work use separate slugs, or separate worktrees — this project's
established pattern.

`assert-artifacts` fails loudly when it finds two candidates rather than picking one silently.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `specify workflow info` fails | YAML/schema error | Read the named step id and field; the CLI validates before running anything |
| A gate paused the run | stdin was not a TTY | `specify workflow status <run-id>`, then `specify workflow resume` |
| `assert-artifacts: no valid change directory` | step 1 did not write `investigation.md`, or the slug is malformed | check `.agents/skills/speckit-engineering-explore/` logs |
| `assert-artifacts: multiple candidates` | two engineering runs in the same tree | finish or discard one |
| `model.md has neither a model nor a 'Not applicable' section` | the model step left a hollow file | re-run the model step |
| A command step reports it cannot dispatch | the `mcode` CLI is not on PATH | `which mcode` |
| Everything looks green but nothing was verified | `run_tests=false` | `results.md` must show `NOT_RUN`; treat the change as unverified |

---

## Deliberately not in 0.1.0

- **No automated artifact validator.** A gate should be written against documents that have
  survived real use, not a format that may still change. When it arrives, the first check is
  `AC-NN` ↔ test-id binding in both directions.
- **No CI gate change.** `.specify/**` is absent from the `changes` paths-filter in `ci.yml`, so a
  workflow-only PR does not trigger the `static` job.
- **No automatic ADR creation** — the last step only points at `singularity-adr`.
- **No `fan_out`/parallel steps** — file-ownership collisions make them a net negative without a
  named owner per file.
- **No issue-registry writes** — see above.