---
title: "The formatting gate judges the sources the push touches"
date: 2026-11-07
status: accepted
tags: ["tooling", "gates", "ktlint", "pre-push"]
---

# The formatting gate judges the sources the push touches

## Context

The pre-push hook ran ktlint over the whole tree that was being pushed: the
step was `scripts/ktlint-lint.py --root @TREE@`, and the script checks every
Kotlin source of the tree when it is given no files.

That made the formatting gate the one gate that judged everything. On
2026-11-06 the tree carried **58 ktlint findings** in two files —
`shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupImporter.kt`
and `shared/src/jvmTest/kotlin/com/singularity/todo/core/backup/BackupKeepExistingTest.kt`
— a defect that was already in `origin/main` and that the branch did not
touch. Every push of the branch was blocked by it, and the ways out were
`--no-verify` or a commit that fixes files the change had nothing to do with.
The same 58 findings are what the `detekt` job of the CI workflow reports,
on `main` as well.

The detekt verdict had already been narrowed this way: the local verdict
judges what the change reaches and CI judges everything
(`2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`). The
formatting gate was the gate that was left judging everything — and it is
the gate a push hits first.

## Idea

Apply the same principle to the formatting gate: the hook judges the sources
the push touches, CI keeps judging every source of the tree, and the
difference is written down where both can be read.

## Decision

- **The formatting step judges the Kotlin sources the pushed commits touch,
  and only those.** The files are the ones the pushed range touches — the
  same notion of "touched" the other `if-touched:` gates of the hook use.
- **The step runs only when the push touches a Kotlin source.** A push that
  touches none pays nothing for the formatting check.
- **The file list travels as the script's positional arguments** — the form
  `scripts/ktlint-lint.py` has taken since it was written. The step runs the
  copy of the script that is *in the tree being pushed*, so a flag that an
  older branch's copy does not know would block a push of that branch for a
  reason that has nothing to do with the branch. The positional form is
  understood by every copy of the script there is.
- **The empty list is not "the whole tree".** The script reads an empty list
  as "no files were given" and falls back to every source of the tree, which
  is why the step is skipped rather than run with an empty list.
- **The record says what each side judges.** `config/hooks/pre-push.json`
  gains a `ktlint` section — what the hook judges, what CI judges, and the
  partiality — and `scripts/check-hook-gates.py` checks the claim against
  the step that runs: a record that claims a narrowing the code does not have
  is a claim nothing checks.
- **CI is unchanged.** The `detekt` job still runs
  `python3 scripts/ktlint-lint.py --require` over the whole tree. CI is the
  authority over the tree; the hook is the fast local gate.

## Rationale

- **A local gate that judges the whole tree reports other people's defects.**
  The author of a change cannot fix them, cannot see why they are there, and
  learns to reach for `--no-verify` — which is exactly the habit the hook
  exists to make unnecessary.
- **The price of the narrowing is real and is paid where it belongs.** A
  formatting defect in a file the push does not touch is no longer found
  before the push. It is found by CI, which judges the whole tree anyway —
  the same place the detekt verdict's untouchable modules are found.
- **The scope is the change, not the tree.** The hook's price now follows
  the change for the formatting gate too: one source costs one source, a
  push that touches no source costs nothing.
- **The narrowing is not a silencing.** The record says what is judged where,
  the gate checks the record against the code, and the CI verdict of the
  pushed commit is printed by the hook itself — the reader of a push sees
  what was judged and what CI will judge.

## Consequences

- A push that touches no Kotlin source skips the formatting gate; a push
  that touches one is judged on that source.
- The 58 findings in `origin/main` no longer block a push of a branch that
  does not touch them. They still make the CI `detekt` job red — CI judges
  the tree, and that is its job.
- A branch cut before this change still pushes: the step runs the branch's
  own copy of the script, and the positional arguments are a form that copy
  has always taken.
- `scripts/check-hook-gates.py` fails when the record does not state the
  partiality of the formatting gate, and when the step that runs is not the
  scoped one.

## Links

- ADR `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`
  — the local verdict judges what the change reaches; this is that principle
  applied to the formatting gate.
- ADR `docs/decisions/2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer.md`
  — ktlint CLI owns the formatting; the step is the same script in CI, in the
  hook and in `lint-changed`.
- `scripts/src/sgate/gatekit/pre_push.py` — the declaration of the step.
- `.githooks/pre-push` — the hook, and the place the file list is built.
- `config/hooks/pre-push.json` — the record of what the hook judges and what
  only CI judges.
- `scripts/check-hook-gates.py` — the gate that keeps the record and the code
  together.
