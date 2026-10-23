---
date: 2026-10-23
status: accepted
deciders: [maintainer]
---

# The verdict reads the tree it judges; it does not correct it

## Context

The pre-push hook answers one question: **does the tree that is being pushed
hold a detekt violation?** The script that answers it
(`scripts/pre-push-verdict.py`) ran detekt with `--auto-correct`, and the
verdict had two signals: the exit code (the findings detekt could not correct)
and the content digest before and after the run (the findings it did correct).

The correction was not free. Measured on 2026-10-23, same tree, same
Gradle invocation, `--rerun`, one module:

| run | time |
|---|---|
| `:shared:detekt` **without** `--auto-correct` | **307 s** |
| `:shared:detekt` **with** `--auto-correct` | **423 s** |
| difference | **116 s (29% of the verdict)** |

The correction also constrained the shape of the run. ADR
`2026-10-17-detekt-auto-correct-works-use-it.md` measured that
`--auto-correct` reaches only the **last** detekt task of an invocation, and
recorded the rule that followed: one Gradle invocation per detekt task. A
change in the rules or in the build judges every module — so a wide change
paid the Gradle configuration phase once per module, on every push.

The parallel option was measured too, and it is not available: two modules
judged at the same time took **703 s** against 57 s + 57 s apart, because
`./gw` holds one of three machine-wide build slots and the machine was busy
with other sessions. The price of a verdict on this machine is a **sum**, not
a `max` — so the levers are the number of modules judged and the number of
Gradle invocations, not the concurrency.

## Decision

- **The verdict does not correct.** The detekt run in the verdict has no
  `--auto-correct`. A verdict that mutates the tree it judges is a verdict
  about a tree nobody pushed.
- **The correction is a separate step** — `just detekt-fix`, which reports
  what it corrected and leaves the commit to the author. The hook's failure
  message names it.
- **The digest stays.** The content digest is compared before and after the
  run, and a tree that changed under the verdict is red. Without
  `--auto-correct` this is a safety net rather than the usual case: it still
  catches any task in the invocation that writes to a source file.
- **All the judged modules cost one Gradle invocation.** The per-module
  invocation rule was a consequence of `--auto-correct` reaching only the
  last task; without the flag, one invocation for every judged module is
  correct and pays the configuration phase once. `--continue` runs the rest
  even when one module fails, so the report names all the findings.
- **The report says which tasks failed.** Gradle's plain console prints
  `> Task :module:detekt FAILED`; the report reads those lines. A run that
  fails without naming a task (a broken build script) is red too, and says
  that the run itself failed rather than naming a module that did not.

## Rationale

- The verdict and the correction answer different questions. "Is the tree
  clean?" and "please fix the tree" have different answers, and a hook that
  answers the second one silently stops answering the first: the tree it
  judges is the tree it changed.
- 116 seconds is not a rounding error. It is 29% of the verdict, on every
  push, for a mutation whose only effect is to rewrite files that then have
  to be re-read, re-committed and re-judged.
- The per-module invocation rule died with the flag that caused it. Keeping
  it would mean paying for a constraint that no longer exists.
- The digest was worth keeping for a reason that has not changed: a file
  that was already modified keeps the same status line, so a status
  comparison cannot see that anything happened. Only the content can.

## Consequences

- A push with a formatting violation is stopped with the findings, not with a
  rewritten tree. The author runs `just detekt-fix` and amends.
- ADR `2026-10-17-detekt-auto-correct-works-use-it.md` keeps its
  measurements — the flag reaches only the last task, and it is not inert —
  and loses the automation part: the pre-push hook no longer corrects.
  `just detekt-fix` and the CI step keep their correcting passes.
- The CI step *Detekt auto-correct before the verdict* still corrects, on
  purpose: it treats the correction itself as the finding, so CI names the
  files a developer has to fix. It is a different surface with a different
  job, and it is not changed here.
- The verdict is faster twice over: 116 s from the missing correction, and
  one configuration phase instead of one per module for a wide change.
- `scripts/tests/test_pre_push_verdict.py` holds the new shape: one
  invocation for all the judged modules, no `--auto-correct` in it, and a
  run that fails without naming a task is red.

## Links

- `scripts/pre-push-verdict.py` — the verdict
- `.githooks/pre-push` — the hook that calls it
- `scripts/tests/test_pre_push_verdict.py` — the contract
- [2026-10-17-detekt-auto-correct-works-use-it.md](2026-10-17-detekt-auto-correct-works-use-it.md) — the measurement that is kept
- [2026-10-23-the-local-verdict-judges-what-the-change-reaches.md](2026-10-23-the-local-verdict-judges-what-the-change-reaches.md) — which modules the verdict judges
