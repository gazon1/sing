---
name: speckit-engineering-verify
description: 'Run the checks named in verification.md and record their real outcome in results.md as PASS, FAIL, NOT_RUN, BLOCKED or NOT_APPLICABLE. Eighth step of Engineering SDD; never reports an unrun check as passing.'
disable-model-invocation: true
---

# Engineering SDD — Verify

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug` and `run_tests`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`.

- Read: `verification.md`, `spec.md`, `design.md`, and the code under test.
- Write: `openspec/changes/<slug>/results.md`

## Goal

Run the verification, and account for it honestly. This step produces **evidence**, not confidence.

### If `run_tests=false`

Run **no** test command. Not a quick subset, not a compile-only check presented as evidence.

Every check in `verification.md` is recorded `NOT_RUN`, and `## Tests not executed and why` says
plainly that verification was not performed and that `results.md` therefore establishes nothing
about correctness. This is a legitimate outcome. An unverified change labelled as verified is not.

### If `run_tests=true`

1. Run the **exact** commands from `verification.md`'s `## Commands and expected outcomes`. Do not
   substitute a faster command and report it as the one that ran.
2. Record each check's real output. A command that failed is `FAIL` — record the failure text.
3. Run the validation commands from `design.md`.
4. Record acceptance criteria one by one against their evidence.

### Write `results.md`

```
# <title>

## Summary
## Implemented changes
## Deviations from the approved plan
## Acceptance criteria results
## Tests executed
## Tests not executed and reasons
## Build, lint and static analysis
## State-model consistency
## Remaining risks
## Follow-up work
## Documentation and ADRs touched
```

Each check carries exactly one status:

| Status | Meaning |
|---|---|
| `PASS` | executed, and it passed |
| `FAIL` | executed, and it found a problem |
| `NOT_RUN` | not executed |
| `BLOCKED` | cannot be executed — state the cause |
| `NOT_APPLICABLE` | does not apply — state the justification |

## State-model consistency

For each transition in `model.md`, report one of:

- **Confirmed** — the code does what the table says, and a named test exercises it.
- **Divergent** — the code does something else. Describe the difference precisely.
- **Untested** — the transition is modelled but no scenario covers it. Say so.

This section is a report, not a fix. Divergences belong to `converge`.

## Forbidden

- Reporting a check as `PASS` that was not executed. This is the one failure this whole pipeline
  exists to prevent.
- Editing `verification.md` to match what happened. A strategy that was wrong when written stays
  wrong in the record; that is data, not noise.
- Editing `spec.md`, `model.md`, `design.md`, `tasks.md` or application code. If a test fails, the
  finding is the output — do not fix the code here.
- Replacing a failing command with one that passes and describing it as the same check.
- Reporting a green build as a passing test. Compilation is not verification.
- Summarizing away the failures. Every `FAIL` gets its real output.

## Done When

- `results.md` has every required section.
- Every check named in `verification.md` appears with exactly one status.
- Every `PASS` has real output from an executed command.
- `run_tests=false` produced no test execution and no `PASS`.
- `## State-model consistency` covers every modelled transition.