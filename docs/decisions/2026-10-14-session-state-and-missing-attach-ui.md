---
title: "Session state semantics and missing AUTH-ATTACH-01 UI"
date: 2026-10-14
status: open
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

## 2. `migrateAnonymousTo` has no UI entry point (AUTH-ATTACH-01 gap)

`AuthRepository.migrateAnonymousTo(email, password)` is fully implemented
in every repository variant:

- `SupabaseAuthRepository` — real Supabase `attachEmail` call
- `DevAuthRepository` — in-memory session transition
- `FakeAuthRepository` — test stub
- `StubAuthRepository` — now correctly transitions `Anonymous → SignedIn`

However, **there is no UI to trigger it**:

- `AuthIntent` has `SignIn`, `SignUp`, `SignInAnonymously`, `SignOut`
- **No `MigrateAnonymousTo` intent**
- `AccountSettingsScreen` has no "Attach account" button
- No `auth_attach_account_button` test tag

`Maestro/flows/auth/07-account-attach.yaml` was written as a **future stub**:
it will only run once the UI is implemented.

**Impact:** The entire anonymous→account upgrade journey is implemented but
unreachable. Users who start offline or as guests cannot attach their
local data to a Supabase account.

**Required work (2026-10-14 — step 1 done, steps 2-3 removed as stub):**
1. ✅ Add `AuthIntent.MigrateAnonymousTo(email, String, password: String)` to `AuthViewModel`
   - `AUTH_ATTACH_ACCOUNT_BUTTON` tag and `07-account-attach.yaml` flow were removed
     as premature — no production UI exists to drive them.
2. Add `SettingsNavigator.openAttachAccount()` stub and `AttachAccount` destination
3. Wire button in `AccountSettingsScreen` (only shown for `Session.Anonymous`)
4. Implement `AttachAccountScreen` — reuse `LoginScreen` with `AuthIntent.MigrateAnonymousTo`
5. Wire in nav graph (Android + JVM)
6. Re-add `AUTH_ATTACH_ACCOUNT_BUTTON` to `TestTags` when UI is wired
7. Write `07-account-attach.yaml` Maestro flow against real UI IDs

---

## 3. StubAuthRepository was broken for anonymous sessions

Before this session, `StubAuthRepository.signInAnonymously()` was a no-op
(`Result.success(Unit)`, session stayed `SignedIn`). This meant any E2E
flow that tried to exercise the anonymous path would silently not work.

**Fixed:** `signInAnonymously()` now sets `_currentSession = Session.Anonymous(...)`.  
**Also fixed:** `migrateAnonymousTo()` now correctly transitions
`Anonymous → SignedIn` instead of no-op.

---

## Links

- `Session.kt` — domain model
- `AuthGuard.kt` — navigation guard
- `StubAuthRepository.kt` — E2E stub
- `07-account-attach.yaml` — stub flow (waits for UI)
- `deferred/reminder-tile-is-only-reachable-from-its-own-preview.md` — REMINDER Tile resolved (deleted)
