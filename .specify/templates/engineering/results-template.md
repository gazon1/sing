# Results — <title>

> Written by `speckit-engineering-verify`; `## Divergences` appended by `speckit-engineering-converge`.
> This file is **evidence**, not confidence. Every status below is backed by a command that ran, or
> by an explicit statement that it did not.

## Summary

<What was implemented, and whether it is verified. If verification did not run, say so here, in the
first paragraph — not in a footnote.>

## Implemented changes

<Per task: what changed, and where.>

| Task | Status | Files |
|---|---|---|
| T001 | done | `path/to/File.kt` |
| T002 | blocked — <cause> | — |

## Deviations from the approved plan

<Anything `implement` did that `design.md` or `tasks.md` did not authorize. "None" is a valid
answer; a silent deviation is not.>

## Acceptance criteria results

| Criterion | Status | Evidence |
|---|---|---|
| AC-01 | PASS | `<command>` → <output> |
| AC-02 | FAIL | `<command>` → <the failure> |
| AC-03 | NOT_RUN | — |
| AC-04 | BLOCKED | <cause> |
| AC-05 | NOT_APPLICABLE | <justification> |

### Status vocabulary

| Status | Meaning |
|---|---|
| `PASS` | executed, and it passed |
| `FAIL` | executed, and it found a problem |
| `NOT_RUN` | not executed |
| `BLOCKED` | cannot be executed — cause stated |
| `NOT_APPLICABLE` | does not apply — justification stated |

**A check that was never run is never described as passing.** When `run_tests=false`, every row
above is `NOT_RUN` and this document establishes nothing about correctness. That is a legitimate
outcome; misrepresenting it is not.

## Tests executed

<Per command: what ran, and what it actually printed. Paste real output, including failures.>

```
<command>
<actual output>
```

## Tests not executed and reasons

| Check | Reason |
|---|---|
| <check> | not run — `run_tests=false` |
| <check> | blocked — <cause> |

## Build, lint and static analysis

| Command | Status | Result |
|---|---|---|
| `./gradlew :shared:jvmTest` | PASS | <counts> |
| `just lint` | PASS | — |

_A green build is not a passing test. Compilation is not verification._

## State-model consistency

<Per `model.md` transition: confirmed, divergent, or untested. `converge` reports this in detail
under `## Divergences`; here it is the summary.>

| Transition | State | Note |
|---|---|---|
| `Idle --start()--> Ticking` | Confirmed | V-01 |
| `Ticking --tick()--> Idle` | Divergent | D-02 |
| `Ticking --reboot()--> ?` | Untested | no scenario |

## Remaining risks

<What is still unproven or unfixed after this change. Each risk is a sentence, not a category.>

## Follow-up work

<Tasks that were identified but deliberately left out of scope, with why.>

## Documentation and ADRs touched

<`docs/` updates, ADR slugs created, and the digest refresh.>

---

## Divergences

> Appended by `speckit-engineering-converge`. Everything above this line is `verify`'s record of
> what actually ran and is never rewritten.

### D-01 — <severity: critical | major | minor> — <one-line description>

- **Spec says:** <cite the `AC-NN`/`INV-NN` and its `file:line` in the document>
- **Code does:** <cite the `file:line` that contradicts it>
- **Impact:** <what a user or future agent would get wrong>
- **Suggested resolution:** <what should change — and note explicitly: not applied here>

### Checked and consistent

- <area checked and found matching, so a reader knows what was actually examined>

### Severity meanings

- **critical** — a stated invariant is false, or data can be lost or corrupted.
- **major** — required behavior is missing or wrong.
- **minor** — documented intent and reality differ without behavioral consequence.