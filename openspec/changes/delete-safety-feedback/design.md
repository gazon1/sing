# delete-safety-feedback — Design

Required: cross-cutting (touches a dozen screens), a new shared UI affordance,
and a product rule that every future delete must follow.

## The classification is the deliverable

The rule the backlog records: row-level deletes get a recovery offer; cascading
and irreversible deletes get an explicit confirmation. Applied to the ten known
sites:

| Site | Class | Reason |
|---|---|---|
| Task (agenda, detail) | Recoverable | row-level, single parent |
| Note | Recoverable | row-level |
| Tag | Recoverable | row-level — but see the tag-group case |
| Saved view | Recoverable | row-level, no dependents |
| Saved search | Recoverable | row-level |
| Attachment | Recoverable | row-level |
| Calendar entry | Recoverable | row-level |
| Project | **Confirmed** | cascades to tasks |
| Tag group | **Confirmed** | cascades to tags and their assignments |
| Stored backup file | **Confirmed** | not reconstructible |

**Write this table into the skill, not only into the spec.** Twelve sites exist
today and the thirteenth will be written next month. A rule in a spec is read
when someone implements the spec; a rule in a skill is read when someone writes
the code. Both, not either.

## Tags are the case that breaks the rule twice

A tag is row-level, so by the rule it is recoverable. A tag *group* is
cascading, so it is confirmed. But a tag that belongs to a group cannot be
deleted independently — the group assignment goes with it. So the tag's
recoverability depends on context, which the two-class rule does not express.

Three options:

1. Treat a grouped tag as cascading (confirm). Simple, safe, and slightly wrong:
   a tag with a group and no other dependents is not a cascade.
2. Make the recovery offer restore the group assignment too. Correct, and makes
   the recovery path's contract wider — it now restores something the user did
   not see deleted.
3. Classify by dependency at delete time rather than statically. Most honest,
   most work, and the classification becomes dynamic and therefore untestable
   with a static table.

**Recommendation: option 2, with the confirmation dialog naming what goes.**
The recovery offer is the more valuable of the two affordances and it should
cover the maximal recoverable set; a dialog is a smaller promise than an undo
and this case is recoverable.

## The recovery offer needs a lifetime, and a visible one

Two defects, one fix area:

- No countdown. The offer appears and vanishes on a platform default the user
  cannot see.
- The offer re-arms on state change rather than running on a timer, so a
  countdown implemented naively will desync from the actual dismissal. **The
  animation and the dismissal must share one clock.** Two clocks is the obvious
  mistake and it produces a bar that fills back up.

Material 3's host renders its action internally and offers no slot to tag it,
which is why a custom host was needed. That same custom host is the natural
place for the countdown, so the two land together rather than separately.

## Recovery failure is the hardest case, and the current shape is wrong

Today: the recovery slot is cleared before the restore runs, the failure is
caught and logged, nothing is shown.

Setting the slot back with an error flag and offering a second attempt is
recoverable but dishonest — it makes a lost write look like a UI glitch. Emitting
a plain failure is honest but leaves the user with a dead end.

**Recommendation: honest failure plus a way back in.** Say the item could not be
restored, and — where the item still exists in a recoverable state — offer to
show where it went. What the current code cannot offer is a retry, because
clearing the slot first is what makes the retry impossible. Clearing it only on
success is a one-line change and is the precondition for everything else here.

## Structural blocker

The task detail screen cannot host a recovery offer today: its state views are
file-level private, so the affordance cannot be placed inside the screen's own
layout. The one screen that works today works by a different route, which is why
it is the only one. Fix that before treating coverage as achievable.

## Reference

`docs/decisions/2026-10-04-multi-select-sheet.md` documents the precedent for
replacing a Material 3 component with a project-owned one when automation and
the platform's slot model disagree. The snackbar host follows the same shape; the
rationale is not restated.
