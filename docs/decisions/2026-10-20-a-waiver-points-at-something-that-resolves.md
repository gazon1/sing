---
status: accepted
date: 2026-10-20
---

# A waiver points at something that resolves, and a rule is read from the source

## Context

Two gate-infrastructure defects, one root.

**First: the exemptions and the parity declarations were prose.** A gate may be
*exempt* from having a positive control — the registry says so, and the reason
names what does the proving instead: "its self-tests pin each part", "the
unittest runner is the proving mechanism". A gate may also be declared to run
on one surface only — CI or the local loop — and the registry says why.

Both answers were sentences. A sentence does not rot loudly: when
`scripts/tests/test_check_gate_wiring.py` was renamed, or the device-tests
workflow's step was renamed, the sentence kept reading exactly as it had, and
the exemption kept exempting. Nothing pointed at anything, so nothing could
break.

The mechanism for a *machine-checkable* pointer already existed —
`Control.tracked_by`, the reference a waived control carries, resolved by
`scripts/check-waiver-references.py` down to the step name inside a workflow.
The exemptions and the parity declarations just did not use it.

**Second: the "script conventions" gate was red on a clean tree, and it was
red for the wrong reason.** The gate enforces R2.1 — gate scripts use Click,
not argparse — and it found three violations. One was real
(`check-gate-registries.py`). One was a false positive
(`check-log-interpolation-allowlist.py` imported argparse only for
`argparse.SUPPRESS`, which is Click's `hidden=True` in disguise). And one was
the gate itself: the detector asked `"import argparse" in content`, a substring
search, and the detector's own source contains that literal — so the file that
*documented* the rule was reported as *breaking* it. A rule enforced by a
substring search is not enforced: it reported the wrong file, the registry
answered with an exemption ("already red on a clean tree … when the Click
migration sweep lands, add a sabotage control and drop this exemption"), and
the exemption became the reason the rule stayed unenforced.

## Idea

A waiver is a pointer, not a sentence. And a rule about source code is read
from the source code, not from its text.

## Decision

**Every exemption and every declared asymmetry carries a `tracked_by`**, and
`scripts/check-waiver-references.py` resolves it.

- An exemption's `tracked_by` names **the file that does the proving** — the
  test, the registry, the generated artefact whose removal is the sabotage.
  Sixteen exemptions now name theirs.
- A `Parity` whose scope is not `both` names **where the asymmetry is visible**:
  a path, optionally with an `#anchor` that names the step inside it. The
  instrumented test floor points at the device-tests workflow's
  "Check the instrumented suite's floor" step.
- The gate resolves the reference the same way it already resolved a waived
  control's: the file must exist, and a workflow anchor must be a **step name**,
  not a string that occurs in a comment.

**The argparse rule is read from the AST.** `check-script-conventions.py` parses
the file and looks for a real `ast.Import` / `ast.ImportFrom` of `argparse`. A
file that merely mentions the words in a string, a comment, or a docstring is
not a violation — and neither is the detector itself. A source that does not
parse is reported as a violation, not as a crash.

**The two false positives are gone and the exemption is dropped.**
`check-log-interpolation-allowlist.py` uses `hidden=True` instead of importing
argparse for a sentinel, and `check-gate-registries.py` is migrated to Click.
The gate is green on a clean tree, so the "script conventions" exemption — the
one that said the gate was red and could not be proven — is **replaced by a
sabotage control**: the control appends an `import argparse` to a gate script
and asserts the gate reports it.

## Rationale

The waiver machinery was already there and already worked; the exemptions and
the parity declarations were simply not using it. A pointer that resolves is
the difference between a claim a reader can check and a claim a reader has to
believe.

Resolving the reference to a *step name* rather than a substring is what makes
the pointer worth having: `Kover verify (detekt-rules)` occurs three times in
`ci.yml`, twice in comments. A substring check would have accepted the
comments, and the waiver would have kept waiving after the step it was granted
for was renamed away.

The AST beats the substring for the same reason. A detector that reads the text
of a file is a detector that reads its own documentation. The rule is about
*imports*, and an import is a syntactic fact — the source has to be parsed to
know it.

Dropping the exemption in favour of a control is the point. An exemption says
"nobody has tried to break this gate"; a control says "breaking it makes it
fail". The gate had been red for a reason nobody could act on, and the
exemption was the reason it stayed that way.

## Consequences

- A renamed or deleted proving file is a gate failure, not a stale sentence.
- The sixteen exemptions each say, in one field, what does the proving. The
  prose stays — it explains *why* — but the pointer is what the machine reads.
- The "script conventions" gate is green, and it is now proven: a gate script
  that imports argparse is reported.
- The registry's `Control` and `Parity` gained a field. Both are frozen
  dataclasses in `sgate.controls`, and the new field is optional in the type and
  required by the gate — the error names the gate, not the dataclass.
- The detector is a parser now, so it is a little more code and a little more
  precise. A file with a syntax error is a finding, which it was not before.
- `GradleTaskParity` was left alone: its premise is checked by task *name* by
  `check_gradle_task_parity`, so it already has a machine check, and its
  reasons point at decisions rather than at a step.

## Links

- `openspec/changes/archive/2026-10-25-the-next-layer-of-contracts/`
- `scripts/src/sgate/controls.py`
- `scripts/src/sgate/commands/_registry.py`
- `scripts/check-waiver-references.py`
- `scripts/check-script-conventions.py`
- `scripts/check-gate-wiring.py`
- `scripts/tests/test_check_script_conventions.py`
