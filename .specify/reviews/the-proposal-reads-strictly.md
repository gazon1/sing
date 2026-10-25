---
change: the-proposal-reads-strictly
verdict: pass
date: 2026-10-24
pr: 1046
reviewed: 9f89a1b92e325418ebede2aaecb8644a1bddd831
documents: sha256:f7571cb7605e9e3d275323e88c26ddc763c1cb22499008e05d98f77bd2a6c7fa
---

## What was reviewed

The six follow-ups the proposal lifecycle left behind, read off the code and
implemented as one change: `gazon1/feat/the-proposal-reads-strictly`.

The review is of the diff of the whole change — the codec, the model, the
planner, the use case, the three surfaces and the test harness — against the
contract the change carries (`openspec/changes/archive/2026-10-23-the-proposal-reads-strictly/`).

## What the change closes

**The two quiet failures on the reading path.** The sealed reader was lenient
(`ignoreUnknownKeys = true`), so a blob a newer build wrote was read with the
new field dropped — and the value that came out was a different, valid value.
And the reader tried the sealed format and then the flat one, so a sealed blob
the sealed reader refused was read by the flat reader as a change with an
empty field. Both are gone: the format is told by the shape of the blob, and
the sealed reader refuses what it does not know.

**The routing that was checked by nobody.** Five predicates in a use case
re-enumerated the model's variants, and a kind that answered none of them fell
through to the branch that assumed a task — failing at runtime, on a user's
device, as a change applied to the wrong thing. Now every kind declares its
target, the target carries the kind, and the planner switches over each group
exhaustively.

**The three stores for one reason.** The store is owned by the decisions, and
a surface reads the reasons they hold.

**The two names that were declared twice.** The field name of a value is
compared with the reader's list in both directions by a test; the identity of a
written row has a name of its own.

**The exemption that said something false.** The agenda's tests sit under the
harness now, the reason was the clock flow that never ends, and the exemption
list is empty.

## What was checked

- **The codec.** The format is told by the shape; the sealed reader is strict;
  a blob with a field this build does not know is refused with the reason; a
  sealed blob is never read as a flat one; the flat format of the builds
  before this one still reads; a blob that is not a blob at all carries a
  reason of its own.
- **The routing.** Every kind declares a target; the target carries the kind,
  so a kind cannot claim a group it does not belong to; the planner's five
  functions switch over their groups exhaustively; the five predicates and the
  separate delete planner are gone.
- **The reasons.** One store, owned by the decisions; the three surfaces
  subscribe to it and none keeps one of its own.
- **The names.** The identity of a row cannot be missing and cannot collide
  across kinds; the field names are compared with the reader's list in both
  directions.
- **The harness.** The agenda's tests name the kind of ViewModel they are
  testing; the exemption list is empty; the gate that keeps it empty still
  runs.
- **The runs.** `:shared:compileTestKotlinJvm` — clean; the targeted suites
  (proposals, agenda, arch, notes) — 698 tests, no failures; `just lint-changed`
  — no findings; `just detekt-fix` — 0 findings in every module.

## What was not verified

- **The compiler checks were not verified by breaking the code.** The
  exhaustiveness of the planner's `when` over each group, and the target's
  refusal of a kind that claims the wrong group, are enforced by the compiler
  — but nobody watched the build fail on a deliberately mis-routed kind. The
  claim rests on the type rules, not on an observed failure.
- **The residual limit of the strict reader.** A field the older build knows
  *and* has a default for, which the newer build dropped, is defaulted by the
  older build. Closing that needs a version envelope in the blob; it is named
  in the ADR and not done.
- **A device on the previous release.** The sealed format is not readable by a
  build that only knows the flat one: such a build shows the change as a change
  it cannot read, with the reason. That is the price of the format, stated in
  the ADR, and it is paid only between two releases.
- **The flat reader's removal.** The plan is a checklist now, with the trigger
  and the order; the removal itself is a follow-up.
