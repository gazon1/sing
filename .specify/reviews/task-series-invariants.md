---
change: task-series-invariants
verdict: fail
date: 2026-10-23
pr: 976
---

## What was reviewed

The whole change: `openspec/changes/task-series-invariants/` — the proposal,
the design, the tasks and the `tasks` spec — against the code it describes
and against `docs/decisions/2026-10-22-the-series-says-one-thing-about-the-work.md`.

The reviewers were separate agents given the diff (`git diff origin/main...HEAD`),
the ADRs and the OpenSpec change, and nothing else: no history of writing the
code, no pressure to find nothing wrong. They were asked to judge the branch
against the contracts it states, not against taste.

## The first review: FAIL

Two blockers and five should-fix items, all of them in the write path rather
than in the presentation:

1. **Blocker — the write path rewrote the rows that already agreed.**
   `realignSeries` compared whole entities and copied the source's `updatedAt`
   onto the member, so every member of a series was rewritten — and its
   timestamp rewound — on every single write, even one that changed nothing
   about the work. That contradicts the spec ("SHALL NOT write the rows that
   already agree") and the ADR.

2. **Blocker — `updateAll` never persisted the tags.** The tags live in the
   cross-ref table, so merging two series left the joined rows with the old
   tags in the database while the task said the new ones.

3. **Should-fix — the repository kept a second list of the shared fields**
   instead of going through `TaskSharedField`; the domain object was used only
   by tests.

4. **Should-fix — the spec said the realignment is scoped by the user the
   incoming row belongs to, while the implementation scoped it by the active
   profile.** The two said different things.

5. **Should-fix — the headline behaviours had no check that could fail.** The
   ADR claimed the repository contract test covered the write path; it did
   not, and the fake the contract test runs against cannot cover it.

6. **Should-fix — a spec scenario contradicted the code**: the scenario said
   the remaining occurrence of a two-member series keeps the series id, while
   the code — and the rule "a series of one is not a series" — clears it.

## The second review: the fixes hold, one more blocker

The second pass checked the fixes against the old code and found that the two
blockers were genuinely fixed — and that a third one was still there, hiding
behind the first:

7. **Blocker — an ordinary write took the tags off the rest of the series.**
   The repair took the tags of the task it was handed, and the task the edit
   path hands it was read through `get()`, which carries no tags at all — they
   live in the cross-ref table. Every other member of the series therefore
   looked divergent on the tags, was rewritten, and had its tag rows deleted.
   The push that followed carried the empty tag set, so the loss would have
   travelled to the other devices.

   The fix: the source's tags are read from the database, not taken from the
   caller's copy of the task, and the pull path writes the tags of the row that
   arrives before it repairs the series — a pull that wrote the row and lost
   the tags would leave the series saying two things about the work.

8. **Should-fix — the timestamp claim was inert.** The repair stamped the row
   with `now`, so the guarantee "the member's own timestamp never moves
   backwards" was not the thing the code did. It now says what is true: the
   repaired row carries the time of the repair and never an earlier time than
   the member already had.

9. **Should-fix — the spec and the ADR still described the pull path in terms
   the code did not implement.** Both now say that the device repairs the rows
   of the profile it is on, and stores a row of another profile as it is.

10. **Nit — the tags were compared as a list**, so the same tags in a
    different order read as a divergence and rewrote the whole series for
    nothing. They are compared as a set now.

## What was done about it

The verdict stays **fail**, because the reviews found something: that is what
this file is for. The findings were fixed in the same branch:

- `realignSeries` asks `TaskSeriesInvariants.membersToRealign` which members
  differ and what they become; the repository decides *when*, the domain
  decides *what the series says*. The source's tags come from the database.
- The pull path writes the incoming row, its tags and the repair of the local
  series as one unit of work.
- `updateAll` writes the tags of the batch into the cross-ref table.
- The specs were corrected to say what the code does — the detach scenario and
  the pull-path scoping — and the ADR's rationale was corrected with them.
- `TaskSeriesWritePathTest` (Room-backed) covers the behaviours that had no
  check: the renamed occurrence realigns the series, the rows that already
  agree are not written again, a member edited later is not rewound, an
  incoming row realigns the local series, a foreign row touches nothing, a
  batch write carries the tags into the database, an ordinary write does not
  take the tags off the series, and the occurrence's own fields survive the
  repair.

## Links

- ADR `docs/decisions/2026-10-22-the-series-says-one-thing-about-the-work.md`
- ADR `docs/decisions/2026-10-23-the-offer-comes-before-the-navigation.md`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskSeriesWritePathTest.kt`
