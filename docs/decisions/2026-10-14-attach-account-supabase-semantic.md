---
title: Attach account — Supabase identity-linking semantic
adr: 2026-10-14-attach-account-supabase-semantic
status: accepted
---

## Context

`AuthRepository.migrateAnonymousTo(email, password)` lets a user who started a
guest session attach permanent credentials to it, converting a `Session.Anonymous`
into a `Session.SignedIn` without losing any local data.

The feature exists because REQ-UA-005 requires that "an anonymous user's existing
tasks end up belonging to the account they then sign up with, and the move be
all-or-nothing."

Two implementation strategies are available:

1. **Identity linking** (what Supabase does): keep the same anonymous `user_id`,
   add email/password credentials to it. Rows already owned by that `user_id` are
   already the account's — nothing moves.

2. **Data transfer**: export the anonymous user's rows, create a new account,
   import the rows under the new account's `user_id`. This is what most cloud
   migrations do; it is also fragile (partial failure orphans data) and redundant
   when the provider is already doing (1).

## Decision

Use Supabase identity linking. The anonymous session's UUID does not change when
credentials are attached, so every row already written under that UUID is
immediately accessible under the new email/password identity.

`SupabaseAuthRepository.migrateAnonymousTo` calls `gateway.attachEmail(email, password)`,
which in turn calls the Supabase `goto link_identity` flow for the current user.
The local repository then verifies the post-link `user_id` matches the pre-link
`user_id` — a safety net for any provider that might issue a new ID — before
reporting success.

## Rationale

- **No data movement**: all rows written during the anonymous session are already
  under the correct `user_id` after linking. A separate transfer would be a
  second, redundant operation with its own failure modes and no benefit in the
  all-succeeded path.

- **All-or-nothing by construction**: Supabase's linking is atomic from the
  client's perspective. If the link call returns a session, the credentials are
  attached to the identity that owns the data. If it throws, nothing is changed.

- **The `user_id` check is a defence-in-depth guard**, not the primary logic.
  Supabase guarantees the id does not change. The check exists so that any future
  provider regression (or misconfigured custom provider) would surface as a
  failed attach rather than silently orphaning data.

- **Simpler failure handling**: the only failure mode that matters is a
  network/validation error before the link is attempted. Post-link failures are
  architecturally impossible under identity linking; with data transfer they
  would require a compensation transaction.

## Consequences

- Anonymous users who attach an account see their tasks, notes, projects, and
  settings immediately accessible after sign-in on any device — no import needed.

- The anonymous session object is updated in place; the local database `user_id`
  column is unchanged. Sync does not need a special migration path.

- If a provider were to issue a *different* `user_id` after linking (violating
  the assumption), `migrateAnonymousTo` would return
  `AppError.Unauthorized(code = "auth.ownership_changed")` and the session would
  remain `Session.Anonymous`. The UI shows an error; the user can retry.

- Attaching an account does **not** change the session's `userId` value.
  `Session.ownerId` is identical before and after a successful attach.

## Links

- Supabase Auth: [Identity linking](https://supabase.com/docs/guides/auth/identity-linking)
- `AuthRepository.migrateAnonymousTo` — `core/auth/AuthRepository.kt`
- `SupabaseAuthRepository.migrateAnonymousTo` — `core/auth/AuthRepository.kt`
- `AuthGateway.attachEmail` — `core/auth/AuthGateway.kt`
- REQ-UA-005 — requirements source
