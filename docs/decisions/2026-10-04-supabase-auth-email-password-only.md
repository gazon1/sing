---
title: Supabase auth is email and password only; the OAuth skeleton is deleted
date: 2026-10-04
status: accepted
---

# Supabase auth is email and password only; the OAuth skeleton is deleted

## Context

`core/auth/oauth/` shipped in MR-4 as a skeleton: PKCE verifier and challenge
generation with platform-specific secure random, token expiry and refresh helpers, a
JWT id-token parser, and a serializable data model. It is well written and it has never
been called. No ViewModel, no repository, no DI binding, no route.

`2026-09-30-dead-code-deleted-and-oauth-kept.md` removed two dead symbols from
`OAuth.kt` and explicitly **kept the file**, on the grounds that deleting it is a
statement about the product — "we are not doing OAuth" — and a dead-code sweep should
not make that call on its own. The finding was parked in the deferred backlog as issue
#38 with the question left open: *is Supabase OAuth still planned?*

Supabase auth + sync is now being built. That forces the question, because the choice
determines what the auth layer looks like from the start.

## Decision

**Email and password, on both platforms. The OAuth package is deleted.**

Google and Apple sign-in are out of scope for this work. If they are wanted later,
they are added as a *new* provider behind the same auth interface — not as a revival of
this skeleton, which hardcodes a redirect-URI model and a `secret-tool`-adjacent
`secureRandomBytes` seam that nothing else needs.

## Rationale

The number of users this app has does not justify an OAuth provider. The cost is not
the initial integration — it is the permanent surface: a redirect URI to host and
register per platform, a loopback listener on Desktop, an intent filter on Android, a
custom-tab or system-browser dependency, and a callback path that has to survive
process death. All of that exists to avoid asking a user for a password, in an app
where the password is the only credential they have.

Email and password has one real cost, and it is the one that actually decides this:
GoTrue will refuse to hand out a session for a new account until the address is
confirmed, so the confirmation flow has to be turned off explicitly. Left at its
default, sign-up appears to succeed and then leaves the user staring at a signed-out
app. That is a one-line setting and it must be set deliberately, not inherited.

## Consequences

- `core/auth/oauth/` is deleted: four shared files, two platform `actual`s for secure
  random bytes, three test classes. Nine files of green tests removed, protecting
  nothing — no test exercised a call path that production does not have.
- The two dead-symbol baseline exemptions for `PKCE` and `OAuthTokenRefresh` are
  removed with them.
- Backlog entry `core-auth-oauth-is-entirely-unwired` (#38) is closed. The
  `2026-09-30` decision to keep the file is not reversed retroactively — it was correct
  at the time, and the entry is what forced the question to be asked.
- Password storage, reset and rate limiting are GoTrue's problem, not the app's. That
  is the main thing being bought.
- `AuthViewModel` remains untested. It was blocked on this decision: with no reachable
  sign-in flow there was nothing meaningful to assert. The email+password
  implementation makes it testable, which is a separate task.
- Deleting the OAuth skeleton removes the project's only use of platform secure random
  bytes. Nothing else needed it.

## Links

- `docs/decisions/2026-09-30-dead-code-deleted-and-oauth-kept.md` — the sweep that
  deliberately left the file, and why
- `docs/decisions/2026-09-23-oauth-pkce-refresh-helpers.md` — what was built and then
  removed
- `docs/decisions/deferred-backlog.md` — `core-auth-oauth-is-entirely-unwired`
- `openspec/changes/supabase-auth-and-sync/` — the change this belongs to
