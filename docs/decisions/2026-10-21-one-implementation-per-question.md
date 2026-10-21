---
title: "One implementation per question: a reader that has two answers is a defect"
date: 2026-10-21
status: accepted
tags: [tooling, scripts, gates]
---

# One implementation per question: a reader that has two answers is a defect

## Context

The script-tooling audit (`docs/engineering/script-tooling-audit.md`) found four
places where this repository answers the same question twice, in the same
language, in the same tree:

| Question | Second answer | Where the answers disagreed |
|---|---|---|
| What does this Kotlin file say, minus its comments? | `find-unwired-surfaces.py`, `check-dependency-usage.py` | a `//` inside a string literal hid the rest of the line |
| Where does this class body end? | `scanners/test_classes.py` | a second brace counter, over already-blanked text |
| What tags does this Maestro flow declare? | `traceability/links.py`, `traceability/maestro_suggest.py` | one stopped at the first `---`, one kept a closed block open |
| Which command does this line-continued block run? | `check-gate-wiring.py` | a second copy of the continuation regex |

Each divergence had a direction, and every direction was the quiet one:

- The dependency gate's regex did not know that `//` inside a string literal is
  not a comment. A line like `val msg = "http://x"` lost everything after the
  quote, so a dependency that *was* referenced on that line looked unreferenced.
  The gate under-reported usage, and it did so silently.
- The Maestro readers disagreed about whether a flow had tags at all. A flow
  whose `tags:` block sits in a later document was invisible to the suggester
  and visible to the runner.
- The two continuation regexes were identical that day. Identical is not the
  same as shared: two copies are two answers waiting to drift.

The repository already had a canonical reader for the first question —
`scanners/source_text.py`, one character walk with nesting-aware block comments
and string-literal handling, written after a Kotlin parser was measured and
rejected (ADR `2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates`). Two
scripts did not use it.

## Idea

**A question that two modules answer is one question with two answers, and one
of them is wrong.** The fix is not "be careful"; it is to delete the second
answer.

The shared reader is the one that already exists, and the ones that do not yet
get one: a new module, in the package that already holds readers, next to the
question it answers.

## Decision

**Every reader of a text question lives in one place, and the others call it.**

- **Kotlin source is read by `scanners.source_text`.** `find-unwired-surfaces.py`
  and `check-dependency-usage.py` no longer carry a regex of their own. The
  canonical reader *blanks* a comment instead of deleting it, so the line
  numbers a detector reports still point at the line the reader saw.
  `check-dependency-usage.py` now sees the usages its regex used to hide.
- **A class body ends where `scanners.source_text.line_depths` says it ends.**
  The second brace counter in `scanners/test_classes.py` is gone.
- **A Maestro flow's tags are read by `scanners.maestro_flow`.** The runner
  (`emulator.maestro._flow`), the traceability scanner
  (`traceability.links`) and the suggester (`traceability.maestro_suggest`)
  now read the same block by the same rule — the runner's, because the runner
  is what filters on it. The reader returns the line each tag was declared on,
  which is what the traceability scanner's error messages need.
- **A continued command is joined by `workflow_gradle.join_continuations`.** The
  rule is public, and `check-gate-wiring.py` calls it instead of carrying a
  second copy of the regex.

**The consolidation changes no answer on this corpus, and that is the claim
that was measured, not asserted.**

| Consolidation | Corpus | Before vs after |
|---|---|---|
| comment stripping | 1676 Kotlin files, 447 test classes | 0 differences in the inventory |
| class body | 1676 Kotlin files, 447 test classes | 0 differences |
| Maestro tags | 105 flows | 0 differences — and the old Python reader and the old shell reader also agree with it on all 105 |
| line continuations | every workflow | same regex, now shared |

## Rationale

- **The failure direction of the old code was the quiet one.** The dependency
  gate could report a used dependency as unused; a false alarm in a gate is
  cheaper than a silent hole, but it is still a gate whose answer depends on
  which copy of the reader ran. After the change there is no copy to choose.
- **Measured equality is what makes the change safe to review.** A consolidation
  that changes what a gate sees needs the new answer to be proven against the
  old one over the whole corpus, not against a fixture. The three numbers above
  are that proof, and they are reproducible from the tree.
- **A shared reader is a place to fix the next bug.** The dependency gate's
  blind spot was a bug in a regex nobody owned. Now it is a bug in
  `scanners.source_text`, which every reader inherits the fix of.
- **The runner is the authority for what a tag is.** The runner decides which
  flows execute. A reader that disagrees with the runner is not "another view" —
  it is wrong about a fact the runner acts on, and the traceability layer would
  link scenarios the runner never runs.

## Consequences

- `scripts/find-unwired-surfaces.py` and `scripts/check-dependency-usage.py`
  import `scanners.source_text`; `scripts/check-gate-wiring.py` imports
  `workflow_gradle`. The `scanners` and `workflow_gradle` modules are now
  depended on from outside `scripts/src`, which is what they are for.
- `scanners.source_text.strip_comments` blanks rather than deletes. Two call
  sites changed shape accordingly, and the unit tests now assert the blanking
  semantics — including that a nested block comment ends at the second `*/` and
  that a `//` inside a string literal is not a comment.
- The Maestro reader removes a trailing YAML comment *before* it removes the
  surrounding quotes. The order matters: a quoted value followed by a comment
  ends in a quote that is not the closing one, and stripping quotes first left
  it behind as part of the tag. The old Python reader had that defect; the
  shell reader did not.
- `scanners/maestro_flow.py` is a new module with one reader and one
  line-numbered accessor. The three consumers agree on every flow in the tree,
  and `scripts/tests/test_maestro_flow_reader.py` asserts that agreement
  against the real flows rather than against a copy of the rule.
- A fourth copy of the tag reader — `scripts/maestro-flow-tags.sh` — has no
  caller left. It is a separate deletion, tracked with the rest of the dead
  code rather than smuggled in here.

## Links

- `docs/engineering/script-tooling-audit.md` — the audit, the findings (F5, F6,
  F7) and the round this belongs to.
- `2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates` — why the canonical
  reader is a character walk and not a parser.
- `scripts/src/scanners/source_text.py` — the Kotlin reader.
- `scripts/src/scanners/maestro_flow.py` — the Maestro tags reader.
- `scripts/src/workflow_gradle.py` — the continuation join.
