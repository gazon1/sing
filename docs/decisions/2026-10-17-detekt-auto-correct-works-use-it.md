---
title: "detekt --auto-correct is not inert — and a dropped flag hides the difference"
date: 2026-10-17
status: accepted
tags: [build, testing, process, tooling]
---

# detekt --auto-correct is not inert — and a dropped flag hides the difference

## Context

`main` went red on run 39005284790 with

```
> Task :shared:detekt FAILED
Analysis failed with 36 issues.
```

18 distinct findings across five files, all formatting or naming:
`StringTemplate`, `Indentation`, `ArgumentListWrapping`, `MultiLineIfElse`,
`MaximumLineLength`, `FunctionLiteral`, `PropertyName`, `NoUnusedImports`, and
two instances of the custom `NoUnwiredReporterInBinding`.

The obvious move was `just detekt-fix`, and the recipe says in its own comment
that the move cannot work:

```
# --auto-correct is inert in this Gradle configuration (detekt 2.0.0-alpha.6).
# Running it in a loop across modules corrects 0 files — verified with a
# planted violation and byte-level diff.
```

ADR `2026-10-09-detekt-auto-correct-inert-multi-module` records the experiment
behind that comment. The recipe had already removed the flag, so its body was a
report-only pass and its `[doc(...)]` line still read `--auto-correct is inert`.

So there were two claims where there should have been one, and they cannot be
told apart from outside:

1. **the flag does nothing** — a statement about detekt, and
2. **the flag is not passed** — a statement about this recipe.

Both produce a file that never changes after a `--auto-correct` run. The second
one is what was true; the first was never re-measured.

## Experiment (2026-10-17)

Same detekt (`2.0.0-alpha.6`), same module, same Gradle invocation, planted
violation of the exact rule detekt can correct:

```bash
$ cat shared/src/commonMain/kotlin/com/singularity/todo/shell/AutoCorrectProbe.kt
internal object AutoCorrectProbe {
    fun describe(name: String): String = "value is ${name} here"
}

$ ./gradlew :shared:detekt --auto-correct --rerun-tasks
File …/shell/AutoCorrectProbe.kt was modified.

$ grep 'value is' AutoCorrectProbe.kt
    fun describe(name: String): String = "value is $name here"
```

sha256 `f17cb77ed9f6cad9` → `bf5df3e82f24e7b3`. The rule was corrected in place.

Run against the real tree, the same command cleared the 36 findings that had made
`main` red, including two blank-line insertions in production code
(`TagsViewModel`, `AgendaViewModel`) that no hand edit had reached. It also
corrected `SharedHarnessConventionsTest`, `SingleOfKotlinDefaultsTest` and
`SyncEngineIntegrationTest` wholesale.

What auto-correct could **not** fix, and what was left for a human: the custom
rules and the path bug in the next ADR.

## Decision

`just detekt-fix` asks for the correction again, in two passes — correct, then
report — and the flag is back in the recipe.

```bash
./gw :shared:detekt … --auto-correct --rerun-tasks --console=plain || true
./gw :shared:detekt … --console=plain
```

Two details that are not incidental:

- `--rerun-tasks` on the correcting pass. Without it the task can be UP-TO-DATE
  from the verification pass that ran a moment ago, and a task served from cache
  rewrites nothing — which is a third way to observe "inert" and be right about
  nothing.
- The correcting pass ends in `|| true`. Its exit code is not the verdict; the
  second pass's is. A correcting pass that fails must not be read as "nothing
  left to correct", which is the failure mode of a single-pass recipe.

ADR `2026-10-09-detekt-auto-correct-inert-multi-module` is **superseded**. Its
measurement was on 2026-10-09 and is not reproducible now; nothing here explains
why it held for eight days, and a claim that cannot be reproduced is not carried
forward as fact.

## Rationale

- A comment that asserts a tool is broken is a gate on that tool: it stops anyone
  from trying, and it outlives the thing it described. The cost here was eight
  days of hand-editing formatting that a formatter writes correctly.
- The experiment is the deliverable, not the conclusion. "It works now" with a
  planted file and a byte-level diff is checkable; "auto-correct is inert" was
  not.

## Three ways to observe "inert" without it being inert

The first experiment above is not the whole story, and this is the part that
makes the old conclusion so believable. Three *silent* shapes produce a file that
never changes, none of which is detekt being broken:

1. **The flag is not passed.** The recipe had dropped it; that is what was true
   here.
2. **The task is UP-TO-DATE.** Without `--rerun-tasks`, Gradle serves detekt from
   the previous run's inputs and never looks at the file. Measured:
   `:shared:detekt --auto-correct --no-parallel` finished in 658 ms and left a
   planted violation in place.
3. **Several detekt tasks in one invocation.** The flag reaches only the **last**
   detekt task. Measured on 2026-10-17 with a planted `StringTemplate` violation:

   | invocation | rewritten? |
   |---|---|
   | `:shared:detekt` alone | yes |
   | `:desktopApp:detekt :shared:detekt` | yes (shared is last) |
   | `:shared:detekt :desktopApp:detekt` | **no** — reported, wrote nothing |
   | `:shared:detekt :mcp-server:detekt` | **no** |
   | `:shared:detekt :desktopApp:detekt :mcp-server:detekt :detekt-rules:detekt` | **no** |
   | all eight modules, one invocation each | yes |

   A task list therefore corrects one module and silently skips the rest. The
   skipped ones *report* their findings, so the run still fails and the failure
   looks like detekt declining to fix them.

The conclusion "auto-correct is inert" is what you get from every one of these,
and from a probe file that no rule covers. The only measurement that separates
them is a **planted violation of a correctable rule plus a byte-level diff of the
file afterwards** — checking the exit code, or the finding count, checks neither.

Every caller therefore runs **one Gradle invocation per detekt task**: `just
detekt-fix`, `.githooks/pre-push`, and the CI step below.

## The verdict is the diff, not the exit code

A fourth trap, found by running the pre-push hook against a planted violation:
a correcting detekt run that **rewrote** the file ended `BUILD SUCCESSFUL` and
exited 0 — it corrected the violation and then had nothing left to report. The
hook's first version treated a non-zero exit as "files were rewritten" and
therefore passed on exactly the push it was added to catch: the planted
`StringTemplate` violation sat corrected on disk while the hook printed OK.

So the check that matters is a comparison of the tree before and after the pass,
in both surfaces:

- **pre-push**: `git status --porcelain` before the loop versus after. Not "is
  the tree dirty" — uncommitted work in progress is normal, and a hook that
  refused to run on a dirty tree teaches people to reach for `--no-verify`.
- **CI**: a fresh checkout, so `git diff` after the pass, guarded by a
  clean-tree check first (otherwise `git diff` attributes someone else's change
  to the correction).

`just detekt-fix` needs neither: its second, report-only pass is the verdict,
which is the whole reason the two passes stay separate.

## Automation

Both surfaces that stand between an edit and `main` now run the correcting pass
first, so the verdict they report is about a corrected tree:

- **pre-push** (`.githooks/pre-push`): `:shared:detekt` and `:desktopApp:detekt`,
  one invocation each with `--auto-correct --rerun-tasks`, before the Gradle
  suite. A non-zero exit means files were rewritten; the push stops and prints the
  diff, because those files belong in the commit being pushed.
- **CI** (`ci.yml`, step *Detekt auto-correct before the verdict*): the same
  modules the Gradle step below measures — one invocation each — immediately
  before it. No `--rerun-tasks` there: a fresh checkout has nothing to be
  up-to-date against, and the flag would only buy a recompile.

The CI shape is the delicate one. Correcting inside CI and then measuring is the
obvious implementation and it is wrong: the correction is not committed, so a
green detekt step one line later is green on a tree nobody pushed — the
"gate passes a sabotaged input" shape this repository already has an ADR for. The
step therefore treats **the correction itself as the finding**: if the correcting
pass produced any diff, the step fails, names the files, and says `just
detekt-fix` plus an amend. Two guards keep that check honest:

- the tree must be clean before the pass, or `git diff` would attribute an
  unrelated change to the correction and the check would mean nothing;
- the correcting pass is not itself the verdict (`|| true`), while its diff is.

The correction buys nothing in CI — the existing detekt step already fails on the
pushed tree — so the honest summary of the CI step is: it re-reports a violation
that was already red, in a form that names the fix. That is worth the extra pass
only because the ordering it establishes is the same one the recipe and the hook
use, so "what CI measured" and "what I ran locally" are the same measurement.

## Consequences

- Formatting findings are no longer hand-edited. They still fail `just detekt`,
  they still fail the pre-push hook, and they still fail CI — the correcting pass
  does not make anything pass that was failing.
- The next change to `detekt-fix` should keep the two passes separate. Merging
  them makes a formatter failure indistinguishable from a clean tree.
- **Do not collapse the per-module loop into one Gradle invocation.** It is
  faster, it passes every check in this repository, and it silently stops
  correcting every module except the last one. That is the whole defect class
  this ADR exists to close.
- A CI run whose auto-correct step fails is reporting a real defect in the pushed
  commit, not a CI problem. Do not "fix" it by adding `|| true` to the step's
  last command: that is precisely the change that makes the check vacuous.
- `just gate-honesty` still plants violations for the *analysis* path. The
  *correcting* path needs the same treatment — plant a correctable violation, run
  one invocation, assert the file changed on disk — and it would have caught this
  on the first attempt instead of after a wrong ADR, a wrong recipe and a gate
  that passed a sabotaged input.
- The CI step costs one Gradle configuration phase per detekt module. If that
  ever needs to go, the honest way is to drop the step and say so, not to merge
  the invocations.

## Links

- `.just/tests/mod.just` — `detekt-fix`
- `.githooks/pre-push` — the same correcting pass before a push
- `.github/workflows/ci.yml` — *Detekt auto-correct before the verdict*
- [2026-10-09-detekt-auto-correct-inert-multi-module.md](2026-10-09-detekt-auto-correct-inert-multi-module.md) — superseded
- [2026-10-07-detekt-auto-correct-single-task.md](2026-10-07-detekt-auto-correct-single-task.md)
- [2026-10-17-a-moved-working-directory-redefines-relative-paths.md](2026-10-17-a-moved-working-directory-redefines-relative-paths.md) — what auto-correct could not reach
- Run: <https://github.com/gazon1/sing/actions/runs/39005284790>