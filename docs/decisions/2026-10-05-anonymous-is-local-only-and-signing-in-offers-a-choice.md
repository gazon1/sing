---
date: 2026-10-05
status: accepted
---

# Without an account the app is local-only, and signing in offers to keep what was made

## Context

REQ-UA-004 in the unarchived `supabase-auth-and-sync` change requires that signing in
without an account "SHALL create a real provider-side identity, not a locally generated
placeholder", and that data created in that session be scoped to it.

The code satisfied the letter of that and contradicted its purpose. The audit of the
auth/sync test plan (rows AN-01, AN-04; issue #182) found three separate reasons an
anonymous session cannot work as specified:

- The session was never persisted. `AuthRepository.apply` sets
  `Session.Anonymous` and writes nothing to the store, and `restoreSession` requires a
  stored refresh token — so the next cold launch found nothing and started `SignedOut`.
  A new identity was effectively minted per launch, which means a row written yesterday
  belonged to an owner that no longer exists today.
- Sync was disabled for anonymous sessions at all three entry points: push is cancelled
  for `Anonymous` in `SyncEngine`, `push()` returns an empty summary for any non-signed-in
  session, and `SyncRunner` requires a signed-in session.
- Nothing in the client created the profile the protocol expects, and the server's
  per-profile rules were never exercised for an anonymous owner.

The first of these was arguable while anonymous data was disposable. It is not arguable
once the data is the user's real work, which is what this decision makes it.

## Idea

1. Keep REQ-UA-004: a real provider identity, synced like any other, and the "attach"
   reuses that identity. Most consistent with the existing protocol, and the only option
   that needs no new concept.
2. Local-only: no provider call at all, data never leaves the device, and signing in
   asks the user what to do with it. Simplest to reason about and needs no server
   permission for anonymous owners.
3. Deferred registration: local until first sign-in, then uploaded. One code path
   eventually, at the cost of carrying an unattributed state through the whole session.

## Decision

**An anonymous session is local only. The provider is not contacted. Signing in offers
the user a choice: claim what was made into the account, or discard it.**

The requirement that anonymous sign-in produce a real provider-side identity is
withdrawn. It described a mechanism, and the mechanism is not what the user needs.

Two consequences the decision forces, which are part of it:

- **The anonymous session is persisted.** This is no longer optional and is not a
  nicety: a session that is not persisted has a different owner on every launch, and the
  "keep or discard" offer would be made about data the app can no longer attribute.
- **The choice is the user's, and it is a real choice.** Both answers are supported and
  neither is a default the app applies silently. Claiming attributes the anonymous rows
  to the new account; discarding deletes them by owner, through the same path a user
  switch uses.

## Rationale

Option 1 was rejected because it cannot be made to work as written without a server-side
decision nobody has made: whether an anonymous `auth.uid()` is a permitted owner under
the existing policies. Carrying an account-less identity through the whole sync stack to
land on "and the server must allow it" is a large bet on an unanswered question, for a
population — people who have not signed up — whose data is by definition the least
valuable to protect on someone else's machine.

Option 3 was rejected as the largest of the three by a wide margin. It introduces a
third ownership state that every write path, every conflict rule and every screen would
have to understand, and its failure mode is the worst of the set: data that exists
locally, belongs to nobody, and cannot be recovered if the user never signs in.

Local-only is also the honest description of what the app can currently guarantee. Sync
for anonymous sessions is switched off in three places independently, so the code already
behaves this way; the requirement was the thing out of step, not the implementation.

The persisted session is the part most likely to be got wrong, and it is stated here for
that reason. The data-loss corner of *not* persisting is invisible: nothing errors,
nothing is reported, and the tasks are still on screen. They simply belong to an identity
that the next launch will not have.

## Consequences

- REQ-UA-004 is superseded. It cannot be modified where it lives — `user-authentication`
  is still a delta inside an unarchived change, so OpenSpec accepts `ADDED` requirements
  for it and refuses `MODIFIED` — so the replacement is a new requirement that states the
  opposite, and the supersession is recorded in the change that carries it.
- The provider call behind anonymous sign-in becomes dead, along with the test that
  covers it. `REQ-UA-008` (the provider is replaceable) is unaffected and becomes easier
  to hold, because one fewer thing depends on it.
- The "keep or discard" offer is a new surface: a reported state, an action, and a
  count. It is also the first place the user is asked to decide something irreversible
  with their data, which is a strong argument for it being a choice and not a default.
- Rows created anonymously are attributed to a locally generated owner. That id must
  therefore be stable for the life of the install, which is the same requirement the
  device id already has.
- The test plan's rows AN-01 through AN-04 and Q1 are answered and become implementable
  tests. MG-01 through MG-07, which assumed a server-side transfer, do not: there is no
  transfer, and the local re-attribution is the whole of it.

## Links

- Issue #182.
- `2026-09-05-secret-storage-split.md` — where the session store's persistence rules
  come from.
- `2026-10-04-sync-server-schema-and-merge.md` — the per-profile rules an anonymous
  owner would have had to satisfy.
- Test plan §3.3 AN-01…AN-04, §12 Q1.
