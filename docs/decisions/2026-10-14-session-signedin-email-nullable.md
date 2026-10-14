---
title: "Session.SignedIn.email is non-nullable String"
adr: 2026-10-14-session-signedin-email-nullable
---

## Context

`RemoteSession.email` is `String?` because the Supabase auth plugin returns
`user?.email` which is nullable. `SecureSessionStore.currentEmail()` is also
`String?` because the keychain value may not yet have been written or may be
absent after a legacy-store migration.

`Session.SignedIn.email` is `String` (non-null). The question is whether this
is the right choice, or whether it should also be nullable to faithfully
represent the possibility that the email is unknown.

## Decision

`Session.SignedIn.email` is `String`. A `SignedIn` session always carries a
non-null email string.

## Rationale

### The domain requires an email for a signed-in user

A `Session.SignedIn` represents a user who has authenticated with email/password
credentials. The email is not optional metadata — it is the very thing that
distinguishes one account from another, and every operation that creates a
`SignedIn` session does so as the direct result of an email/password
authentication or attachment call.

An anonymous session (`Session.Anonymous`) has no email because the user has
not provided one. A signed-in session always has one by definition: you cannot
sign in with email/password without an email address.

### The nullable seam is already at the right layer

The nullable `RemoteSession.email` is the provider seam. The nullable
`SecureSessionStore.currentEmail()` is the persistence seam. Neither leaks into
the domain because `SupabaseAuthRepository.apply()` normalises both at the
boundary:

```kotlin
apply(remote: RemoteSession, email: String) {
    // remote.email is String?; email is the store's cached value (also String?)
    // The non-null email written into Session.SignedIn is the first non-null
    // of (provider email, stored email) or "" as a last resort.
    val finalEmail = remote.email ?: email.orEmpty()
    val session = Session.SignedIn(email = finalEmail, ...)
}
```

This is intentional: the store is the fallback when the provider has not yet
propagated the email into the session object, and `""` as a last resort keeps
the type non-null without crashing. The fallback is reachable only in a narrow
race condition during session restore, and an empty string in this position
does not affect any user-visible behaviour — the email is used in Settings and
Account screens, both of which are preceded by a network request that will
have a current value.

### Supabase: no signed-in session without an email

In Supabase, `auth.signInWith(Email)` or `auth.signUpWith(Email)` always
requires an email address. The `is_anonymous` flag is orthogonal to the email
field: an anonymous user can have an email attached (and therefore a non-null
email) while still being `isAnonymous = true`. A non-anonymous user — i.e. one
that has completed email/password sign-in or has had credentials linked — always
has an email in the provider's session.

There is no path in Supabase that produces a `Session.SignedIn` equivalent
(i.e. `isAnonymous = false`) without an email address. The scenario "attached
anonymous account has no email before confirmation" does not arise: credential
attachment (`gateway.attachEmail`) does not require email confirmation, and a
non-anonymous session without an email is not a Supabase artifact.

### The alternative (nullable) creates a fragile domain

If `Session.SignedIn.email` were `String?`, every screen and use case that
shows the email would need to handle null. The Settings screen, Account tab,
and any sync or notification that includes the email address would all need
`email ?: "..."` guards. These null checks would be dead code in practice —
the null is never expected to occur — but they would obscure the real logic
and create space for future mistakes (e.g. passing a null-forwarding email to
a notification payload).

Keeping the type non-null makes the expected invariant explicit in the type
system and prevents accidental null propagation.

## Consequences

- `Session.SignedIn.email` is always a `String`. Callers do not need to
  handle null.
- The persistence and provider seams remain nullable; normalisation happens
  once in `SupabaseAuthRepository.apply()`.
- If both `RemoteSession.email` and `SecureSessionStore.currentEmail()` are
  null, the domain receives `""`. This produces an empty-string email in the
  rare race window, which is visible only if the user reaches Settings before
  the next network request. No crash or data loss results.
- Future identity providers that support signed-in sessions without email
  (e.g. phone-only OAuth) would require a deliberate change to this decision.

## Links

- `Session.SignedIn` — `core/auth/Session.kt`
- `RemoteSession.email` — `core/auth/AuthGateway.kt`
- `SecureSessionStore.currentEmail()` — `core/auth/SecureSessionStore.kt`
- `SupabaseAuthRepository.apply()` — `core/auth/AuthRepository.kt`
- `attachEmail` (no email confirmation required) — `SupabaseAuthGateway`
