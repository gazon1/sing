---
change: the-offer-above-the-rows-and-the-state-below
verdict: pass
date: 2026-11-10
pr: 2399
reviewed: 7f83bda475cd839ddd26580ae7a6dbe166a7d386
documents: sha256:1421431e2c2ecd3005355993af16a970b021d5cae754c80355777536aae19d42
---

## What was reviewed

The placement change of PR #2399 against the plan in issue #1942's comment of
2026-11-10:

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorContent.kt`
  — the two supplied-content slots and the order they are drawn in;
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorCallbacks.kt`
  — the slot's KDoc;
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskDetailContent.kt`
  — the call site that fills the lower slot;
- the new `TaskEditorSlotsOrderTest`, the changed `TaskDetailTimeTrackingSectionTest`,
  and the Maestro flows that reach the moved sections;
- the decision record `docs/decisions/2026-11-10-the-editor-slots-keep-their-names.md`
  and the change's own contract.

The review was a second agent's: it was given the diff, the issue's plan and the
decision record rather than the session that wrote them, because an agent
reviewing its own diff inherits the assumptions the diff was built on.

## What the first round found

The first round returned a **fail** against commit `c50716530`, on two blocking
findings. Both were real, and both are closed.

**The fast population failed, and the fast population is what a pull request runs.**
The new `TaskEditorSlotsOrderTest` asserted absence with a hand-written
`onAllNodesWithTag(...)`. `HarnessConventionTest` keeps raw selector calls out
of the test bodies — a selector written by hand is a selector the next reader
has to understand twice, and the helper already says what it means. At
`c50716530` the gate failed with `TaskEditorSlotsOrderTest.kt: 3 raw call(s)`;
at the parent `d8c5aa01e` the same gate was green.

**The slow population failed.** Moving the lower slot below the attribute rows
put the time-tracking chip past the fold of the 1024x768 desktop window.
`TaskTime01ScenarioTest` asserted that the chip was *displayed* without a
scroll, and the assertion failed with "is in the semantics tree but never became
displayed within 5000 ms". The parent passed and `c50716530` failed, so it was a
regression and not a flake.

The second finding is the interesting one, and it is not a defect in the change.
It is what the change *does*: a section that used to be under the description is
now under the last attribute row, and everything that asserted on those sections
as "on screen" has to scroll to them first. The sibling test
`TaskDetailTimeTrackingSectionTest` was updated with a scroll in the same commit;
this one was missed. A move that changes what is on screen changes what the
assertions about that screen have to say, and the ones that are not updated fail
— which is the point of them.

## What closed the findings

- `40d5b0b50` — the time-tracking probe scrolls to the section it probes. The
  probe answers "is the surface reachable", and reaching it is a scroll away.
- `7f83bda47` — the slots-order test asserts absence through
  `assertTagNotExists`, the helper the convention gate points at.

The reviewer re-ran both populations at the branch tip after the fixes: the fast
population is green (96 tests, 0 failures) and the slow population is green
(263 tests, 0 failures). `:desktopApp:detekt`, `check-issue-registry.py` and
`check-openspec-stale.py` are green at the same commit.

## What the placement itself was checked against

The reviewer confirmed the placement against the code rather than against the
commit message:

- `TaskEditorContent.kt:229` draws the offer slot, directly under the
  description and above the attribute rows;
- `TaskEditorContent.kt:456` draws the lower slot, after the Pin row
  (`:437-447`) and before the trailing `Spacer` (`:458`);
- the call site at `TaskDetailContent.kt:591` is unchanged in content, and the
  create screen passes neither slot, so it is unaffected.

The order the issue asked for and the order the code draws are the same one.

## The findings that were not blocking

**The decision record of 2026-10-21 reads "in the detail flow they are sections
under the fields", and this change moves them to be exactly that.** The line was
written when the lower slot was drawn above the attribute rows, so it described
a placement that no longer held; the change makes it true again. The reviewer
flagged it as possibly stale; reading it against the code at `d8c5aa01e` and at
the tip shows it is the other way round. Nothing was edited — a decision record
is not amended because a later change happens to agree with it.

**The detail-flow order assertion anchors on the due-date row rather than the
last one.** The issue's plan asks for exactly that anchor ("the due-date row is
laid out above the checklist section"), and the editor-level test measures
against the Pin row, which is the last row. Both anchors are covered, so the
weaker one in the flow is the plan's own wording rather than a gap.

**The proposal-placement test is unchecked in the change's task list.** That is
deliberate: it needs a proposal-seeded detail screen, which is its own piece of
work, and the issue's brief leaves the proposal placement test out of scope. It
is left open in `tasks.md` rather than ticked, so the next session can see it.

## The naming

The change keeps `bannerSections` and `extraSections` and does not rename them.
The review found nothing to say against that: the defect the issue describes is
a placement, and the placement is what the change fixes. The rename that pull
request 2399 originally carried is recorded in
`docs/decisions/2026-11-10-the-editor-slots-keep-their-names.md`, with the
reason — a rename puts a decision into a name, and while that rename was in
flight the live skill documentation kept naming a slot that no longer existed.
