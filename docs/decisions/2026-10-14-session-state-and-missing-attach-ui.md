---
title: "Session state semantics and missing AUTH-ATTACH-01 UI"
date: 2026-10-14
status: accepted
tags: ["auth", "architectural-gap"]
---

**Context**

While wiring AUTH-SIGNUP-02 to CI and extending StubAuthRepository, two
pre-existing architectural issues surfaced.

---

## 1. Session state is well-defined but deserves explicit record

The `Session` sealed interface has four variants:

| Variant | Meaning | AuthGuard behaviour |
|---|---|---|
| `Session.Loading` | Auth state not yet restored | Spinner |
| `Session.SignedOut` | No session, no identity | LoginScreen |
| `Session.Anonymous` | Guest identity, data local to device | content() — agenda |
| `Session.SignedIn` | Full account, tokens persisted | content() — agenda |

**Key invariant:** `SignedOut` means "no session at all" — the user must
authenticate to proceed. `Anonymous` means "a usable local identity" — the
user can use the app but has no cloud sync or account.

`SupabaseAuthRepository.signOut()` → `SignedOut`.  
`DevAuthRepository.signOut()` → `Anonymous` (BY DESIGN — dev stub keeps the
app usable without a server).  
`FakeAuthRepository.signOut()` → `SignedOut`.

The `accountIdOrNull` extension correctly returns `null` for both
`SignedOut` and `Loading`, and the `ownerOrNull()` private helper in
`SupabaseAuthRepository` also returns `null` for both — so the two
states are equivalent for sync purposes.

---

## 2. `migrateAnonymousTo` had no UI entry point (AUTH-ATTACH-01 gap, closed — see Resolution)

`AuthRepository.migrateAnonymousTo(email, password)` is fully implemented
in every repository variant:

- `SupabaseAuthRepository` — real Supabase `attachEmail` call
- `DevAuthRepository` — in-memory session transition
- `FakeAuthRepository` — test stub
- `StubAuthRepository` — now correctly transitions `Anonymous → SignedIn`

At the time, **there was no UI to trigger it**:

- `AuthIntent` had `SignIn`, `SignUp`, `SignInAnonymously`, `SignOut`
- **No attach intent**
- `AccountSettingsScreen` had no "Attach account" button
- No `auth_attach_account_button` test tag

`Maestro/flows/auth/07-account-attach.yaml` was written as a **future stub**:
it would only run once the UI existed.

**Impact:** The entire anonymous→account upgrade journey was implemented but
unreachable. Users who start offline or as guests could not attach their
local data to a Supabase account.

**Required work at the time (2026-10-14), all landed — see Resolution:**

1. `AuthIntent` for the attach submission
2. `SettingsNavigator.openAttachAccount()` and the `AttachAccount` destination
3. A button in `AccountSettingsScreen`, shown only for `Session.Anonymous`
4. `AttachAccountScreen`
5. The destination in the navigation graph on both platforms
6. `AUTH_ATTACH_ACCOUNT_BUTTON` in `TestTags`
7. `07-account-attach.yaml` against the real UI ids

---

## 3. StubAuthRepository was broken for anonymous sessions

Before this session, `StubAuthRepository.signInAnonymously()` was a no-op
(`Result.success(Unit)`, session stayed `SignedIn`). This meant any E2E
flow that tried to exercise the anonymous path would silently not work.

**Fixed:** `signInAnonymously()` now sets `_currentSession = Session.Anonymous(...)`.  
**Also fixed:** `migrateAnonymousTo()` now correctly transitions
`Anonymous → SignedIn` instead of no-op.

---

## Resolution

Resolved 2026-11-15: the journey is reachable and the gap this ADR recorded is closed.

Verified: `AuthIntent.AttachAccount` is handled by `AuthViewModel.attachAccount`;
`AccountSettingsScreen` draws the entry point (`AUTH_ATTACH_ACCOUNT_BUTTON`) and calls
`SettingsNavigator.openAttachAccount()`; the screen is `AttachAccountScreen`; the
destination is registered in the shared `AppEntries` graph, so Android and desktop
both carry it; and `Maestro/flows/auth/07-account-attach.yaml` drives the real UI.

The intent is named `AttachAccount`, not the `MigrateAnonymousTo` proposed above; the
transition it performs — an anonymous session becoming a signed-in account with its
local data attached — is the same one. The attach screen asks what to do with the
offline rows (`LocalDataDisposition.Keep` or `.Discard`) and `LocalAccountClaimer`
signs in before it moves anything, so a refused sign-in leaves the local data in
place.

## Links

- `Session.kt` — domain model
- `AuthGuard.kt` — navigation guard
- `StubAuthRepository.kt` — E2E stub
- `07-account-attach.yaml` — the attach journey on the device
- `deferred/reminder-tile-is-only-reachable-from-its-own-preview.md` — REMINDER Tile resolved (deleted)
