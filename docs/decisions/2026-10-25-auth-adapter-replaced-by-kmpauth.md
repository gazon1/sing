---
title: "The auth adapter is replaced by KMPAuth, and the SDK keeps the session"
date: 2026-10-25
status: accepted
slug: auth-adapter-replaced-by-kmpauth
---

# The auth adapter is replaced by KMPAuth, and the SDK keeps the session

## Context

The authentication layer was three stacked modules: a port (`AuthGateway`), a vendor seam
(`SupabaseAuthGateway`), and a hand-written credential store (`SecureSessionStore`) that kept four
keychain values and migrated a third copy out of plain-text preferences.

Two things were wrong with it, and both were findable without leaving the repository.

**The store could not hold a half-written session, but it could hold a half-*migrated* one.** The
old layout wrote the access token, the refresh token, the address and the owner id as four
separate keychain entries and undid a partial write when one of the four failed — because a
half-written identity is worse than none: the device reads back two fields of the new session
beside two fields of the old one, and concludes it is signed in as an owner the fields disagree
about. One value cannot be half-written.

**The refresh's rejection answer was unreachable in production.** `REQ-UA-003` asks for two
distinct answers — "the provider rejected this refresh token" and "the provider could not be
reached" — and only the first may end a session. The old gateway collapsed both into a null and
then turned the null into `AppError.Unauthorized("auth.no_session")`, so the repository's
sign-out branch could only be reached by a test. A user on a train, whose refresh failed because
the network hiccuped, was signed out.

**And the SDK was keeping a second copy of the session, in plain text.** `AuthConfigDefaults`
leaves `sessionManager` unset, and the default is the SDK's own `SettingsSessionManager`, which
writes the session — tokens and all — into the platform's settings store. On Android that is
`SharedPreferences`: the very thing `DataStoreSessionStore` had been moved out of. The project
installed the `Auth` plugin with every default, so every sign-in wrote the bearer token to disk
in the clear, next to the copy the application *thought* was the only one.

## Idea

Let the SDK own the session, and let KMPAuth's Supabase backend own the credential model — with
the application's own port still the seam everything else in the codebase sees.

The port does not change shape. The domain still speaks `RemoteSession` and never names a vendor
type, and `REQ-UA-008` still says so. What changes is who is behind it.

## Decision

1. **KMPAuth's `SupabaseAuthBackend` replaces the hand-written sign-up, sign-in and sign-out.**
   Not the global `KMPAuth` facade: that is a process-global registry with its own Koin
   initializer, which fights the lazy client and the project's Koin rules. The backend is built
   per call over the resolved client, so a client rebuilt after a project change is never
   stale.

2. **Four operations stay direct SDK calls**, each because KMPAuth's version of it cannot express
   what this application needs: the magic link (KMPAuth's `sendSignInLinkToEmail` does not say
   that the account must not be created), the magic-link exchange (KMPAuth parses a `token_hash`
   or a PKCE `code`; this application's link carries a plain token), the password change (KMPAuth
   has none), and the refresh (KMPAuth's `currentUserIdToken` returns an access token and cannot
   say "rejected").

3. **The credential store becomes the SDK's own `SessionManager`.** `SecureSessionManager` holds
   one value — access token, refresh token, owner id, address, expiry — in the keychain, and the
   plugin is built with it. The SDK's session and the application's session are one session in
   one place, and the plain-text copy stops being written at all.

4. **The plugin is configured not to load the session by itself.** `autoLoadFromStorage = false`,
   and the seam's `currentSession()` performs the load. The load — and the migration it triggers —
   then happens inside the restore's bound, instead of inside whichever caller happened to ask
   for the client first.

5. **`alwaysAutoRefresh = false`.** The application owns the refresh, as it always did. A plugin
   that refreshes on its own republishes a session the application did not ask for.

6. **The store's migration writes before it erases.** The new key first, the old source second,
   and a failed write erases nothing. The reverse order has a window in which a crash leaves the
   user signed out of their own account with no way back.

7. **A record without an owner is kept, not dropped.** The old plain-text migration wrote a
   token and an address and no owner id. `REQ-UA-013` says a plain-text token is *moved into the
   secure store and erased*; dropping it would leave the token in plain text forever while
   telling the user they were signed out.

## Rationale

**Why the seam's `currentSession()` asks the store directly instead of letting the plugin load
it.** The plugin's own load path reports *every* failure as "no session" — it catches
`Throwable` and returns null. That is right for it and wrong here: a keychain that cannot be
read is not a signed-out user, and the difference is the difference between the user being sent
to the login screen and the user being told what went wrong. The seam therefore asks the session
manager and hands the session to the plugin afterwards, so the plugin knows who is signed in and
its own requests do not go out as a stranger.

**Why the refresh's rejection is detected by the HTTP status.** The plugin's `refreshSession`
returns a non-null session and throws; there is no null to return. The two answers are therefore
told apart by what the plugin threw: a 400 or a 401 is the provider refusing the token, and
anything else — including a 5xx from a provider that is unwell — is a failure the next attempt
may survive. This is the mechanism the design assumed; the SDK's API is what it is, and the port's
contract (`Result.success(null)` for a rejection, `Result.failure` for a failure) is unchanged.

**Why one value and not four.** `REQ-UA-012` asks for a store that holds no incomplete session,
and one value is the strongest possible answer to it: there is no partial state to have.

**Why the migration is not done in the constructor.** Storage is suspending and can fail, and the
manager is built in the Koin graph before any screen exists. Doing it lazily, on the first read
of the session, means the work happens where a failure has somewhere to go.

## Consequences

- **The plain-text copy of the session stops being written.** The SDK's default session manager
  was writing the bearer token into `SharedPreferences` on every sign-in. Installing the
  application's own manager stops it. The copy that older installs already wrote is *not* erased
  by this change: it lives under a key derived from the project URL, in the SDK's settings store,
  and the application cannot address it from `commonMain`. It should be erased by a follow-up
  that reaches the SDK's settings on each platform.
- **The repository no longer touches the credential store.** It reads the live session through the
  port and publishes what the provider holds. Its four-field reconciliation is gone, and so is
  the code that read a rejected token's absence as "signed out" — that branch is now reachable.
- **The sign-out order is now: revoke at the provider, then clear the device.** The plugin's
  `signOut()` does exactly that, and a provider that cannot be reached still leaves the local
  session cleared, because the seam clears it on the failure path. The window in between is the
  provider's round trip; a process that dies in it leaves the user signed in, which is the
  opposite of what a sign-out asked for.
- **The vendor-confinement rule now covers two vendors.** `com.mmk.kmpauth` joins
  `io.github.jan.supabase`, each with its own allowlist. A seam is a seam for *one* vendor: the
  file that keeps the session speaks Supabase, and the file that signs in speaks KMPAuth.
- **`kmpauth-supabase:3.0.6` is built against `auth-kt:3.7.0` while the project pins `3.8.0`.**
  Gradle resolves the higher one. All 74 references KMPAuth's bytecode makes into the SDK exist
  in 3.8.0 with the same descriptors, so the binding holds — but a mismatch surfaces as a
  runtime `NoSuchMethodError`, not a compile error, and the seam's own tests are what would
  catch it.

## Links

- The change: `openspec/changes/replace-auth-with-kmpauth/` — `design.md` holds the alternatives
  and the rejected ones; `spec.md` holds the invariants and the acceptance criteria; `tasks.md`
  holds the 17 steps; `results.md` holds what each gate actually answered.
- The capability delta: `openspec/changes/replace-auth-with-kmpauth/specs/user-authentication/spec.md`
  — `REQ-UA-008` now covers every vendor SDK, and `REQ-UA-023` records that a stored session
  survives an update that changes the store's representation.
- The Google OAuth decision, which put KMPAuth in the repository in the first place:
  `docs/decisions/2026-10-24-google-oauth-kmpauth-signin-server-side-refresh.md`.
