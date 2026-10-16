---
title: "A positive control must reproduce the real defect, and a shell command is not a line"
date: 2026-10-16
status: accepted
tags: [tooling, testing, process, ci]
---

# A positive control must reproduce the real defect, and a shell command is not a line

## Context

`main` went red on run 38961950540 with:

```
ERROR: gate 'build-output-readers' PASSED a deliberately sabotaged input
       (.github/workflows/ci.yml)
```

The gate's script was not at fault: its eight unit tests were green, including
one that puts a reader after a second Gradle invocation and asserts the finding.
Two separate defects sat behind the report, and neither was visible from the
script's own tests.

**1. The control mutated the wrong thing.** It appended
`- name: late coverage pass / run: ./gradlew :koverVerify` to the *end of the
file*. The last job in `ci.yml` is `nightly-alert`, which has no build-output
reader and a single test-capable invocation — and the gate skips any job with
fewer than two test-capable invocations, by design. The probe therefore exercised
the gate's ignore branch, not its rule. A control that lands in the ignore branch
passes forever, whatever the rule does.

**2. The parser read physical lines.** `_invocations` in
`scripts/check-gradle-test-population.py` treated one physical line as one
command. `ci.yml`'s producing invocation is written across fourteen:

```yaml
./gradlew --continue \
  --no-build-cache \
  :shared:jvmTest :desktopApp:test \
  -Ptest.tags=fast,slow
```

Per-line parsing saw `./gradlew --continue` — a launcher with no task names, so
`test_capable=False` — and then discarded every continuation line, because none of
them repeats `./gradlew`. The invocation that *produces* the results did not
exist as far as the caller was concerned, so `check-build-output-readers.py`
promoted the next real invocation (`:koverVerify`) to producer and exempted it.
The violation the gate's own docstring names as its reason to exist — `Flake
analysis` reads `shared/build/test-results/jvmTest` after `:koverVerify` — was
structurally unreachable. The same fault hid the reader: its path sits on a
continuation line too, so a reader scanning one line at a time saw no
`build/test-results` and skipped the step.

Two errors, opposite in sign, cancelling into a green.

## Idea

- **A.** Exempt the gate (`GATE_EXEMPTIONS`) until a control can be written.
- **B.** Relax the rule so the appended probe fires: count any job with one
  test-capable invocation as having a writer.
- **C.** Make the control reproduce the ordering the rule exists to forbid, and
  teach the parser shell grammar.

## Decision

**C.** Two changes.

1. `_logical_lines(script)` joins backslash-continued lines into one logical
   command, numbered at its first physical line, and both gates parse logical
   lines. A trailing backslash is what the shell itself treats as continuation,
   so this is grammar, not a heuristic about this repository's formatting.

2. The control replaces the `Coverage floors` step with itself plus a reader of
   `shared/build/test-results/jvmTest` — the real defect, in the real job,
   verbatim — instead of appending an invocation to the end of the file.

The violation message now names the invocation's tasks
(`… tests:45 [:koverVerify] is a test-capable Gradle invocation that runs before
it`), so the finding identifies the command to move without a second look at the
file.

## Rationale

- A control is evidence, and evidence has to be of the thing the gate claims to
  detect. `Edit(kind='append', …)` to a file whose last job has no readers is a
  mutation the rule explicitly ignores; it proves the ignore branch works.
- The two parser errors cancelling each other is why the unit tests were green.
  Every one of them used single-line commands, which is the shape the parser used
  to be right about. `scripts/tests/test_check_gradle_test_population.py` now has
  `ContinuedLinesAreOneCommand`, and `test_check_build_output_readers.py` has the
  mirrored class, with every command in both fixtures spanning lines.
- Exempting the gate (A) or widening it (B) would each have made CI green while
  leaving `ci.yml` free to break in exactly the way the gate exists to prevent.

## Consequences

- `scripts/check-build-output-readers.py` imports `_invocations` and
  `_logical_lines` from the population gate rather than re-implementing them. One
  parser, one place to fix; the two gates cannot disagree about what a
  test-capable invocation is without one of them being wrong.
- Any future control written as "append something to the end of this file" deserves
  a second look: most files end with a job that a given rule does not govern.
- Line numbers in findings are now first-physical-line numbers, which is what a
  reader editing the file needs.

## Links

- `scripts/check-build-output-readers.py`, `scripts/check-gradle-test-population.py`
- `scripts/src/sgate/commands/_registry.py` — `GATE_CONTROLS['build-output-readers']`
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md`
- `docs/decisions/2026-10-15-gate-registry-carries-controls-as-data.md`