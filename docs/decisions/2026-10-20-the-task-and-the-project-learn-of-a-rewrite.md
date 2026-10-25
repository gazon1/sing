---
status: accepted
date: 2026-10-20
---

# The task and the project learn of a rewrite the same way the note does

## Context

The note editor learned to tell "the user typed" from "somebody else rewrote the
note" — see `2026-10-20-the-editor-is-told-when-the-content-was-replaced.md`. The
task detail and the project detail had the same shape and none of the answer.

Both hold a **bridged** text field: a Compose `TextFieldState` fed by a draft in
the ViewModel. Both observe an entity the repository owns — a task, a project —
and that entity can be rewritten from outside: a sync from another device, a
change made on another screen.

The draft made that rewrite unreachable. `seed()` was idempotent: it initialised
the draft once and ignored every later call. The draft was a snapshot of the
entity as it was when the screen opened, so a rewritten task never reached the
field. The screen kept showing the old title, and the user's next keystroke
wrote the old title back over the new one — the rewrite was not merely hidden,
it was undone.

The echo guard was the second half of the problem. Even with a re-seed, a rewrite
that lands on a value the field *did* produce earlier is indistinguishable from
a lagging upstream, and the guard drops it. That is what the revision token is
for, and the task and the project did not have one: their fields passed no
revision to the bridge.

## Idea

Give the task and the project the note's rule, in the note's terms: the draft
knows when it was replaced, the field is told, and the user's unsaved edits win.

## Decision

The task draft and the project draft carry a **`revision: Long`**, and their
seed is no longer idempotent.

- **The seed tells three cases apart.** The first call initialises the draft.
  The echo of our own save — the draft already holds exactly that text — changes
  nothing. A rewrite from outside is taken, and the revision moves.
- **The revision counts replacements, not edits.** It advances only when the
  content was replaced from outside, never for a keystroke, and never for the
  echo of our own save.
- **The user's unsaved edits win.** A dirty draft is never re-seeded. The
  rewrite waits for the user, and the next write carries the user's text over
  it. The other writer has no stake in the text; the user does.
- **The repository's own echo marks the draft clean.** When the repository holds
  exactly what the draft holds, the write landed, and the baseline moves with
  it. Without that the draft would stay dirty for the rest of the screen's
  life, and every later rewrite would be refused as "the user is typing" — the
  rewrite would never land. The echo is also the one signal that cannot be
  faked by a stale write: a write that landed while the user kept typing does
  not match the draft, so the draft stays dirty and the newer text wins.
- **The field passes the revision to the bridge.** `TaskTitleRow`,
  `TaskDescriptionField` and `ProjectHeroSection` take it and hand it to
  `rememberBridgedTextFieldState`, which lifts the echo guard for that value.

The *contract* — who may rewrite this field, and what carries the rewrite — is
published per site in `.agents/skills/singularity-todo-text-field-states/SKILL.md`
and held honest by `TextFieldRewriteContractTest`. The task title, the task
description, the project name and the project description move from "the value
itself" to "the value and a revision token", because all four observe an entity
that a sync can rewrite.

The contract test grew a second half. It used to check that a field claiming a
revision *has* one; a field that passed `revision = 0` passed the same check as
one that passed a live counter. Now the registry also names **the file that
declares the counter**, and the test fails when the revision is a constant — a
token that cannot move cannot say anything — and when the named counter does not
declare a revision.

## Rationale

The alternative was to leave the task and the project on "the value itself" and
say so. That answer is honest, and it is the answer for a field whose upstream
only ever writes values the field never produced — the chat input, the search
queries, which the ViewModel *clears*. It is the wrong answer for a field whose
upstream is a synced entity, because that upstream can write back a value the
field produced.

Re-seeding without a revision would have been a half-measure: it would fix the
rewrite that lands on a *new* value and keep losing the one that lands on a
value the field produced. Two different defects, one rule, one counter.

Marking the draft clean after a write is the part that looks like bookkeeping
and is not. Without it the "the user's edits win" rule becomes "the first
keystroke of the screen's life wins forever", and the rewrite never lands at
all. The rule needs the draft to know the difference between *unsaved* and
*saved*, and the save is the only event that draws that line.

The user's edits winning is a decision, not an oversight — the same one the note
editor made.

## Consequences

- A task or a project rewritten from outside now reaches the field, and the user
  sees the new text instead of the next keystroke overwriting it.
- A user mid-edit keeps their text; the replacement does not land under their
  fingers, and the next save carries their text over the rewrite.
- The drafts carry a field whose only meaning is "the content was replaced N
  times". It is meaningless outside the screen, which is why it lives on the
  draft and not on the domain model.
- A field site that claims a token now has to name the counter it reads, and the
  counter has to be a live one. A frozen `revision = 0` is worse than no token:
  it looks like the contract is honoured.
- The seed is no longer idempotent, so anything that relied on that — a test
  that seeded twice and expected the first value to win — now sees the rewrite
  land. That is the point of the change, and the two draft-state tests say so
  in their names.

## Links

- `openspec/changes/archive/2026-10-25-the-next-layer-of-contracts/`
- `docs/decisions/2026-10-20-the-editor-is-told-when-the-content-was-replaced.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskDetailDraftState.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectDetailDraftState.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldRewriteContractTest.kt`
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — section 9
