# backup-include-remaining-tables — Design

Required: new data model, cross-cutting (touches export, import, format version,
and the restore report), and a rollback-risk change (a wrong ordering can lose
data rather than merely fail to restore it).

## The parent-before-child ordering is the whole risk

The seven data sets are not independent. A checklist item belongs to a task; a
project-tag group assignment belongs to both a project and a tag group; a
reminder belongs to a task or a project. Restoring a child before its parent
produces either a constraint failure or, worse, an orphan that silently survives
the round trip and reappears as a dangling reference after the parent arrives.

Insert order is therefore part of the contract, not an implementation detail:

1. tags, projects (the referenced entities)
2. saved agenda views, saved searches (they reference tags and projects)
3. tag groups, project-tag group assignments (reference tags and projects)
4. tasks, notes
5. checklist items, time entries, reminders (reference tasks, notes, projects)

**Rollback risk:** restoring into a non-empty database with a different existing
row set. The mitigation is that restore is a replacement operation, not a merge —
so a wrong order fails loudly on the first constraint violation rather than
producing a partially-restored state. Verify that this is actually true of the
current implementation before relying on it; if restore is a merge anywhere, this
proposal must state so and the ordering requirement becomes a transaction
boundary instead.

## The orphan case has no good answer

A checklist item in a backup whose task is not in the same backup cannot be
restored without a parent. Options: drop it and report the count, create a
placeholder parent, or fail the whole restore.

Dropping and reporting is the recommended default: it keeps the restore
successful, and it makes the loss visible, which is the property this change
exists to provide. A placeholder parent invents data the user never had and will
confuse them later. Failing the whole restore for one orphan is a denial of
service on a backup that is 99% fine.

**This is a decision, not a derivation.** Confirm it before implementing.

## Profiles: the one genuinely open question

The other seven data sets are scoped rows belonging to a profile. Profiles are
the container. Restoring a backup that contains profiles raises a question the
other seven do not: **which profile is active after the restore?**

A backup can contain several profiles. The restore has to land the user
somewhere, and there is no correct answer that is not a product decision:

- restore into the profile that was active when the backup was taken, recorded
  in the manifest;
- restore into the currently active profile and merge the others in;
- restore all, and require the user to pick.

The third is honest and the most work. The first is cheapest and is only correct
if the manifest records it, which it currently does not.

**Recommendation: split this.** Ship the seven row data sets now, where the
answer is mechanical, and treat profile restore as its own change once the
product decision is made. Bundling them means the mechanical 80% waits on the
ambiguous 20%, and a backup that omits seven data sets is worse than one that
omits none but resolves the active profile imperfectly.

## Reference

Architecture rationale for the restore path bypassing ordinary write guards is in
`docs/decisions/2026-09-27-write-layer-soundness.md`. It is not restated here.
That decision is why this change does not route restored rows through the
ordinary write path, and why a bulk-import port is being proposed separately
(issue #82) rather than being folded in here.
