# the-next-layer-of-contracts

Issue: — · Related: `docs/decisions/2026-10-20-the-task-and-the-project-learn-of-a-rewrite.md`, `docs/decisions/2026-10-20-a-waiver-points-at-something-that-resolves.md`, `docs/decisions/2026-10-20-the-toolchain-pins-live-in-one-file.md`

Status: **accepted**
Tags: **rewrite contract gate integrity toolchain**

## What

Close the eight gaps the last layer left open. They are eight gaps in four
places, and each of them is the same shape: **a rule that nothing checks**.

1. The task detail and the project detail have the note editor's defect —
   a rewrite from outside never reaches the field, and the next keystroke
   undoes it. The note learned to tell a replacement from a keystroke; the
   task and the project did not.
2. The rewrite contract test checks that a field claiming a revision *has* one.
   It does not check that the revision can **move** — `revision = 0` passed.
3. `NoteRewriteWatcher` — the class that decides whether a note was rewritten
   under the editor — had no test at all.
4. The four toolchain version pins were written out twice, in two files, with
   nothing comparing them.
5. The preflight that checks the gate tooling was installed ran on
   contributors' machines and never in CI.
6. The gate registry's exemptions and its declared CI/local asymmetries
   explained themselves in prose. Prose does not break when the thing it names
   is renamed.
7. The "script conventions" gate was red on a clean tree, and it was red for
   the wrong reason: it detected `import argparse` with a substring search, so
   it reported the file that *documented* the rule instead of the file that
   broke it.
8. Two test doubles lived in production packages, in the directories the
   architecture scans read as production code.

## Why

Each of these is a rule that looks enforced and is not.

**The task and the project.** `TaskDetailDraftState.seed()` and
`ProjectDetailDraftState.seed()` were idempotent: they initialised the draft
once and ignored every later call. A task rewritten by a sync never reached the
field; the screen kept showing the old title, and the user's next keystroke
wrote the old title back over the new one. The rewrite was not hidden — it was
undone. The note editor had been fixed with a revision token; the same token,
the same rule, the same place in the bridge, was one file away in both of these
screens.

**The contract test.** `TextFieldRewriteContractTest` asserted that a field
registered as carrying a revision contains the text `revision =`. A field that
passed a constant satisfied it. The registration said "this field is told when
it is rewritten"; the code could have said "this field is told, always, with
the number zero", and the build would have stayed green. A token that cannot
move cannot say anything.

**The watcher.** `NoteRewriteWatcher` decides three things: whether the note
changed, whether the draft is dirty, and whether the id matches. All three
decisions were prose in a KDoc. The class the rewrite contract is *built on*
was the one class in it with no test.

**The pins.** The `lychee-bin` pin appeared in `setup-local-tools.sh` and in
`setup-python-tools/action.yml`. A bump on one side is a silent divergence: the
developer's `lychee` and the runner's `lychee` disagree about what a dead link
is, and the difference shows up as "it passes here and fails there".

**The preflight.** `scripts/ci/preflight-gate-tools.sh` names the missing tool
when one is missing — and it ran only in `check.sh`. CI discovered a broken
tooling install as a traceback inside a gate that was supposed to be checking
something else, several steps after the cause.

**The exemptions.** Sixteen exemptions and one declared CI/local asymmetry
explained themselves in sentences. A sentence does not rot loudly: when the
proving test was renamed, the sentence read exactly as it had, and the
exemption kept exempting. The mechanism for a checkable pointer already
existed — `Control.tracked_by`, resolved down to a workflow step name — and the
exemptions simply did not use it.

**The conventions gate.** `"import argparse" in content` matched the
detector's own source, because the detector's source contains that literal. So
the gate reported itself, reported a script that imported argparse only for
`argparse.SUPPRESS` (which is Click's `hidden=True` in disguise), and reported
one real violation. The registry answered with an exemption saying the gate
was red on a clean tree and could not be proven. The exemption was the reason
the rule stayed unenforced.

**The doubles.** `MapFileSystem` and `FakeSecureStorage` sat in
`core/files/` and `core/security/`. The architecture scans read
`/test/fakes/` as "not production"; a fake in `core/` was scanned as
production code, and the unwired-surface detector had to carry two baseline
exemptions for them.

## The shape of the fix

Every one of the eight is the same move: **a claim becomes a pointer, and the
pointer is checked**.

- The task and the project drafts carry a revision that moves when the content
  is *replaced*, and the field passes it to the bridge.
- The contract registry names the file that declares the counter, and the test
  fails when the revision is a constant.
- The watcher gets a test that drives a live draft, not a snapshot.
- The pins live in one file, and a test fails when a literal appears anywhere
  else.
- The preflight runs in CI's static job, next to the tooling it checks.
- Every exemption and asymmetry carries a `tracked_by`, and the gate resolves
  it.
- The rule is read from the AST, and the exemption is replaced by a sabotage
  control that appends an `import argparse` and watches the gate report it.
- The doubles move to `test/fakes/`, and the baseline exemptions for them go
  with them.

## What not to do

- Do not drop the bridge's echo guard to make the rewrite land. The guard is
  what stops the field's own push from replacing its own text; removing it
  moves the caret to the end on every keystroke.
- Do not re-seed a dirty draft. The user's unsaved edits are worth more than
  the other writer's rewrite, and a rewrite that lands under their fingers is
  worse than a rewrite that waits.
- Do not compare timestamps to tell a rewrite from an echo. The upstream that
  rewrote the entity may have rewritten it *to* the value the field already
  produced; no comparison distinguishes that from an echo. The upstream has to
  say so, and a counter is the smallest thing that carries it.
- Do not keep the exemptions and add a "reference" column nobody resolves. A
  pointer that is not checked is a sentence with a file name in it.
- Do not make the conventions gate green by deleting the rule. The rule is
  right; the detector was reading the wrong thing.
- Do not leave the "script conventions" exemption in place once the gate is
  green. An exemption says "nobody has tried to break this gate"; the control
  says "breaking it makes it fail".
