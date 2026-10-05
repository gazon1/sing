---
date: 2026-10-06
status: accepted
title: A profile is owned, and an erase resolves the exact ids before deleting anything
---

# A profile is owned, and an erase resolves the exact ids before deleting anything

## Context

REQ-UA-017 requires that signing into a different account erases the departing account's data,
and that "every profile of the departing account goes and not only the active one". Nothing in
`shared/` implements it — `grep` finds no `switchAccount`, no `AccountSwitch`, no second entry
point. It is new work, and the shape of the delete was not obvious.

The obvious delete is wrong. It looks like every other owner-scoped query in the codebase:

```kotlin
// what the plan implies, and what is wrong
@Query("DELETE FROM tasks WHERE user_id = :ownerId")
```

A profile is not a column. `ProfileEntity` has no `user_id` and no `profile_id`, and neither do
`tasks`, `notes`, `projects`, `tags`, `time_entries` or `agenda_views` — the schema was verified
against `schemas/…/38.json`. The profile survives only as the **shape of the id itself**, in
`ProfileAwareCurrentUser.kt:21-25`:

```kotlin
fun scopedUserIdFor(profileId: ProfileId, userId: UserId): UserId = if (profileId == ProfileId.default) {
    userId
} else {
    UserId.fromString("${profileId.value}/${userId.value}")
}
```

So the departing account's rows are spread across `owner` (the default profile) **and**
`prof-a/owner`, `prof-b/owner`, … for every other profile. `WHERE user_id = :owner` erases one
profile and silently leaves the rest — and it fails in the direction that looks like success: the
user sees an empty task list and no error. The test in `tasks.md` ("two profiles, one switch, both
gone") fails on exactly this, which is the test doing its job.

The `LIKE '%/' || :owner` alternative is worse in a quieter way. It is a *suffix* match, so it
matches any account whose id ends with the departing one: an account `eu1` matches
`WHERE user_id LIKE '%/u1'` when read as a prefix pattern over `prof/eu1`, and a one-character
difference in the id decides whose data is destroyed. A LIKE that decides deletion is the same
class of defect as a LIKE that decides authorization.

Two tables make it worse than the six the plan names. `tasks.md` records `task_tags` and
`task_dependencies` as the child tables that carry no `user_id`. The schema has four more:
`checklist_items` (only `task_id`), `project_tag_groups` (only `project_id`), `ai_proposal_item`
(only `proposal_id`), and `llm_usage` (only `profile_id`). The `EXISTS`-through-the-parent idiom
still applies to the first three, but each reaches a **different** parent, so a single
`EXISTS (SELECT 1 FROM tasks …)` does not cover them.

## Idea

1. **Give `profiles` a `user_id`.** Migration 38→39. The profile stops being an anonymous
   namespace and becomes a row that says whose it is.
2. **Resolve ids in Kotlin, delete by exact list.** Read the ids belonging to the owner, decide
   in Kotlin, then `DELETE … WHERE id IN (:ids)`.
3. **Delete children through their own parent, per table.** Four `EXISTS` queries rather than one.

## Decision

**All three.**

**Option 1, on the strength of what the other two need.** Erasing by owner cannot be written
correctly while the profile has no owner: the query has to guess, from the string, which
prefixes belong to the departing account — which is option 2's parsing, applied to data whose
meaning was never recorded. Recording it makes the question answerable instead of inferred.

The column is nullable at the schema level for the same reason `sync_outbox.owner_id` is NOT NULL
but seeded: a profile created before sign-in belongs to nobody yet, and a NOT NULL column would
force a placeholder identity that later reads as a real owner. An unowned profile is a true state,
not a missing value — the account-less session owns its data, not a profile.

**Option 3, over a LIKE.** A `LIKE` that decides which rows a user loses is the defect this whole
change exists to prevent; replacing `=` with `LIKE` keeps the shape of the mistake and hides it
further. The exact-list delete cannot over-match, because every id in it was matched against
something that says what it is.

**Option 2 also removes the reason the suffix rule was attractive.** With `profiles.user_id`, the
set of a departing account's profiles is a question the database answers exactly. The composed
`user_id` values are then *derived* from that set (`scopedUserIdFor`) rather than parsed out of
strings — parsing is what made the suffix ambiguity possible in the first place.

**The deletes are grouped by operation, not by table.** `OwnerEraseDao` holds every delete
and `OwnerScopeDao` holds the two parent-id reads, rather than one method per entity DAO.
detekt's `TooManyFunctions` is what forced the split, but it names something real: the
order among these statements is load-bearing, and an order is easier to keep correct when
the statements sit together. Splitting reads from writes also makes the sequence a shape —
a caller holding an `OwnerScopeDao` is about to read, and one that has moved on to
`OwnerEraseDao` has already read what the child deletes will be scoped by.

## Rationale for the ordering

Resolution is read-only and reversible. Deletion is neither. Splitting the work this way means the
half that can be wrong — deciding what belongs to whom — is verifiable on its own, by a test that
asserts a resolved id list, before anything is destroyed. A test that asserts "the rows are gone"
cannot distinguish "resolved correctly and deleted" from "resolved wrongly and deleted", and that
distinction is the entire risk of this operation.

## Consequences

- `profiles` gains a column, so **SCHEMA_VERSION becomes 39** and the exported schema needs a
  `39.json`. Migration 38→39 adds the column and leaves existing rows NULL — a profile's owner
  is not derivable after the fact, and guessing one would file an account's profile under
  another. This is the same reasoning as the 37→38 outbox backfill, and the same refusal.
- Every site that constructs a `ProfileEntity` must supply an owner or null. `ProfileRepositoryImpl`
  is where the owner is known, so that is where it is written.
- `scopedUserIdFor` stays the single source of truth for the composed id. The erase must use it
  rather than string concatenation, or the two can disagree about what a profile's rows look like.
- The four child tables each need their own scoped delete, and each reaches a **different** parent —
  three through `tasks`, one through `projects`. An `EXISTS` against the wrong parent deletes nothing
  and reports success, which is the harder failure to notice. A fifth, for `llm_usage`, depends on the
  question answered below.
- **The erase is not atomic.** `withTransaction` does not exist anywhere in `commonMain` today, so these
  are separate statements and a failure part-way leaves the database with some of the owner's rows gone
  and some not. That gap is fixed by the unit-of-work decision in
  `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it`. Until it is, the ordering guarantee is
  that the erase cannot leave one account's data under another's ownership — the failure the user cannot
  undo — as opposed to leaving it partly erased, which they can retry.
- **`llm_usage` and `profiles` rows themselves are not erased by owner in this change.** They become
  ownable, but erasing a profile's own row needs a decision about whether a profile outlives the
  account that made it, which is a separate question from "whose data is this". Recorded as debt
  rather than decided here.

## Links

- `account-switch-and-clock-drift` REQ-UA-016, REQ-UA-017.
- Test plan §6 OB-04, OB-05.
- `2026-10-05-switching-users-delivers-then-erases-the-departing-user` — the ADR for the
  deliver-then-erase order this implements.
- `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it` — the atomicity question, decided
  separately.
- Issue #209 for the outbox ownership work, the precedent for refusing to guess an owner.