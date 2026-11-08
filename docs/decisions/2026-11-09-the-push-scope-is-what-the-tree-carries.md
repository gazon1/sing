---
title: "The scope of a push is what the tree carries"
date: 2026-11-09
status: accepted
tags: ["tooling", "gates", "pre-push", "tests"]
---

# The scope of a push is what the tree carries

## Context

The pre-push hook judges what the push touches: the formatting gate judges
the Kotlin sources the push carries, and the gate-suite step judges the test
modules the push touches
(`2026-11-07-the-formatting-gate-judges-the-sources-the-push-touches.md`,
`2026-11-08-the-gate-suite-judges-the-modules-the-push-touches.md`).

The scope was read from `git log --name-only`, and that reading had three
holes. A review of the branch found them:

- **A merge carries files that the log does not show.** `git log --name-only`
  prints the diff of every commit — and the diff of a *merge* is computed
  against its parents, so the plain log says nothing about it. A file born in
  the resolution of a merge is therefore invisible to the scope: the push
  carries it, and no gate judges it. Before this change the hook judged the
  whole tree, so the file was judged; the narrowing lost it.
- **A path with non-ASCII letters is a path git hides.** Git escapes such a
  path by default (`"shared/src/main/kotlin/Caf\303\251.kt"`), and the escaped
  form matches no pattern the gates use: the file is in the push and no gate
  sees it.
- **The history is not the tree.** The scope was the set of names the history
  mentions. A module the push deletes, or renames away, is in that history and
  is not in the tree — and a name the tree does not hold is a name the runner
  turns into a failed test. The push was blocked by a module that was not
  there.

The helpers of the suite were a fourth gap, and a quieter one: a file under
`scripts/tests/` that is not a test module is part of the suite, and the
modules that import it are reached by a change to it. The scope named test
files only, so a change to `scripts/tests/git_sandbox.py` — imported by six
modules — judged nothing.

## Idea

The scope of a push is the tree the push carries, and the history is only the
way of finding out what that tree holds. Three rules follow: read the history
the way git writes merges, read the paths the way the filesystem spells them,
and let the tree decide what exists.

## Decision

- **The set of carried files is read merge-aware.** The hook asks git for
  `--diff-merges=first-parent`: every merge is shown against the mainline it
  builds on, which is exactly what such a push carries. Git older than 2.31
  does not know the option; `--cc` (the combined diff) is the fallback.
- **The paths are read unescaped.** Every git call of the scope uses
  `-c core.quotePath=false`, so a path with non-ASCII letters is the path the
  filesystem has.
- **The tree decides what the gate-suite step judges.** A test module of the
  scope is judged when the pushed tree still holds it. A module the push
  deletes, or renames away, is not judged: there is nothing of it to run, and
  a name the tree does not hold is a failed test.
- **A helper of the suite reaches its importers.** A changed file under
  `scripts/tests/` that is not a test module is a helper, and the modules that
  import it are in scope.
- **A step that did not run says so.** A gate skipped because the push
  touches nothing it judges prints `SKIPPED` with the reason — a step that is
  skipped in silence is a step the reader cannot tell from a step that was
  never reached.
- **The record and the gate follow.** `config/hooks/pre-push.json` states the
  scope in these terms, and `scripts/check-hook-gates.py` checks it against the
  code: the formatting step has to be handed the sources the push touches
  (`@TOUCHED_KT@`), the gate-suite step has to derive its scope from what the
  push touched and has to ask the tree what exists.

## Rationale

- **A gate that judges too little lets a defect through.** The narrowing of
  the scope is only safe while the scope is complete. The merge and the
  non-ASCII path were holes in it, and a hole is where a defect walks through.
- **The tree is the thing being pushed.** The gate runs in the tree; the tree
  says which modules are there. Judging a name the tree does not hold is
  judging the history, and the history is not what goes out.
- **A helper is part of the suite.** The suite is not only its test modules;
  it is the modules they import. A change to a helper reaches its importers,
  so it reaches them.
- **Silence is not an answer.** "OK" and "skipped" are different verdicts, and
  the reader of a push is owed the difference.

## Consequences

- A push that carries a merge is judged by the files the merge introduced.
- A push that carries a path with non-ASCII letters is judged by that path.
- A push that deletes or renames a test module is not blocked by that module.
- A push that changes a helper of the suite is judged by the modules that
  import it.
- A gate the push does not reach prints `SKIPPED` and the reason.
- `scripts/check-hook-gates.py` fails when the formatting step is not handed
  the touched sources, when the gate-suite step does not derive its scope from
  the push, and when it does not ask the tree what exists.

## Links

- ADR `docs/decisions/2026-11-08-the-gate-suite-judges-the-modules-the-push-touches.md`
  — the scope of the gate-suite step.
- ADR `docs/decisions/2026-11-07-the-formatting-gate-judges-the-sources-the-push-touches.md`
  — the same principle applied to the formatting gate.
- ADR `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`
  — the local verdict judges what the change reaches.
- `.githooks/pre-push` — the hook, and the place the scope is built.
- `config/hooks/pre-push.json` — the record of what the hook judges.
- `scripts/check-hook-gates.py` — the gate that keeps the record and the code
  together.
