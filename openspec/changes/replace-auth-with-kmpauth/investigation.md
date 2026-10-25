# Investigation — Replace the home-grown auth adapter with KMPAuth's Supabase backend

> Written by `speckit-engineering-explore`. Every claim below needs a citation: `path:line`, or a
> command you actually ran. An uncited claim belongs under `## Hypotheses`.
>
> This document records **what happens today**. It does not decide what should happen — that is
> `spec.md`.

## Request

> Заменить самописную auth систему на KMPAuth

Interpreted as: replace the hand-written auth adapter + token storage with KMPAuth's
Supabase backend. The scope of "auth system" was put to the user; the answer was
**"только слой-адаптер"** (adapter layer only).

## Scope

**In scope**

- The `AuthGateway` implementation (`core/auth/SupabaseAuthGateway.kt`) — rewritten over
  KMPAuth's `SupabaseAuthBackend`.
- The token store (`core/auth/SecureSessionStore.kt`) — replaced by a `SessionManager`
  backed by the app's `SecureStorage` port, plus the SDK owning the session.
- The `AuthRepository` restore path (`core/auth/AuthRepository.kt`) — reads the live
  session instead of four separate keychain keys.
- The `Auth` plugin wiring in `SupabaseClientProvider` — installs the new `SessionManager`.
- DI rebinding in `core/di/CoreDiModule.kt`.
- The vendor-confinement allowlist and its positive control.
- Test doubles and the affected tests.
- The `core/auth-token-storage` capability spec, and the `MODULE-INDEX.md` row.

**Non-goals (explicit)**

- Changing the identity provider. Supabase remains the issuer; RLS and the Supabase schema
  are untouched.
- Google/Apple sign-in **for the account**. `kmpauth-google` stays exactly where it is —
  the Google *calendar* sign-in in `feature/calendarSync/auth/`.
- The offline/local session (`Session.Anonymous`, `LOCAL_OWNER_ID`, `LocalAccountClaimer`,
  the ownership transfer). KMPAuth's `signInAnonymously` is a *provider-side* identity and
  ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice` explicitly
  rejected that model.
- `AuthDomain` email/password validation (`REQ-UA-009`, `REQ-UA-010`).
- The bounded restore deadline (`REQ-UA-015`).
- The backend, the Supabase project configuration and the email templates.
- Any change to `Session`, `CurrentUser`, `core/sync`, or the `feature/*` screens.

## What the code actually does

The "home-grown auth system" is not one class. It is three layers, and only the middle one
is really hand-written.

### Layer 1 — the port (`core/auth/AuthGateway.kt`)

`AuthGateway` (`core/auth/AuthGateway.kt:33`) declares seven operations, all in project
vocabulary, all returning `Result<T>`: `signUp` (:36), `signIn` (:39), `signOut` (:42),
`updatePassword` (:50), `sendMagicLink` (:58), `verifyMagicLink` (:66), `refresh` (:75).
Its currency is `RemoteSession` (`core/auth/AuthGateway.kt:86`) — id, email, two token
strings, one flag. The KDoc at `:18-31` states the intent explicitly: the domain sees
`RemoteSession`, never a `UserSession`, a `UserInfo` or a `SessionStatus`.

**This layer is already the right abstraction and needs no structural change.**

### Layer 2 — the vendor seam (`core/auth/SupabaseAuthGateway.kt`)

`SupabaseAuthGateway` (`core/auth/SupabaseAuthGateway.kt:29`) implements the port over the
`jan-tennert/supabase` SDK, resolving the client per call (`:41`) because no project may be
configured at graph-build time. Every call reads the session back rather than trusting the
return value (`:19-27`).

### Layer 3 — the repository and the token store

`SupabaseAuthRepository` (in `core/auth/AuthRepository.kt`) owns the session state machine:
`_currentSession: StateFlow<Session>`, the pending flag (`REQ-UA-007`), the bounded restore
(`REQ-UA-015`), the local session, and the outbox-untouched rule (`REQ-UA-006`).

`SecureSessionStore` (`core/auth/SecureSessionStore.kt:42`) stores four values in the
platform keychain — access token, refresh token, email, owner id — and owns two hard rules:

- the **plaintext migration ordering**: *write to the keychain first, erase from preferences
  second* (`:21-33`), because the reverse order can strand a user signed out of their own
  account;
- the **partial-write undo**: a session that fails to write completely is erased, not left
  half-written (`:55-71`), because a half-written identity reads as a signed-in owner that
  the fields disagree about (`REQ-UA-012`).

`DataStoreSessionStore` (`core/auth/DataStoreSessionStore.kt:41`) is the pre-upgrade plain
store; the only write left on it is `forgetTokens()` (`:161`). It also owns the device id,
the local owner id and the local-session marker, which are not secrets.

### Consumers

`AuthGateway` has exactly **one** production consumer — `SupabaseAuthRepository`
(`core/auth/AuthRepository.kt`). `AuthRepository` has ~15 production consumers.
`CurrentUser` (`core/auth/CurrentUser.kt:28`) is the fan-out point: it exposes `userId` and
`liveUserId`, and is reached from ~82 production files through `ProfileAwareCurrentUser`.

`core/sync` reads `session.accessToken` in 6 places to authenticate PostgREST/Edge calls.

### The Google sign-in that already uses KMPAuth

`kmpauth-google:3.0.6` is already a dependency (`shared/build.gradle.kts:315`) and is used
in `feature/calendarSync/auth/GoogleConsent.android.kt` for the **Google Calendar**
consent flow. It is a *provider* (an OAuth identity for Google), not the app's account
identity. The 2026-10-24 ADR
(`docs/decisions/2026-10-24-google-oauth-kmpauth-signin-server-side-refresh.md`) records
that decision. **This change does not touch it.**

### What KMPAuth actually offers

`kmpauth-core:3.0.6` defines a provider-agnostic backend contract,
`com.mmk.kmpauth.core.auth.AuthProviderBackend`, with
`signIn(AuthCredential, forceRefresh)`, `signUp(email, password)`, `signOut()`,
`currentUser()`, `currentUserFlow()`, `currentUserIdToken(forceRefresh)`,
`sendSignInLinkToEmail(email, settings)`, `signInWithEmailLink(email, link, linkAccount)`,
`reauthenticate(credential)` and `deleteAccount()`.

`kmpauth-supabase:3.0.6` implements that contract as
`com.mmk.kmpauth.supabase.SupabaseAuthBackend(client: SupabaseClient)` — **it wraps an
existing `SupabaseClient`**, so it can be handed the app's own client from
`SupabaseClientProvider` with no second client and no session divergence. Its Maven POM
depends on `io.github.jan-tennert.supabase:auth-kt:3.7.0`, i.e. the *same SDK family* the
app already uses at `3.8.0`.

---

## Divergences: where KMPAuth cannot be used verbatim

Five of the seven `AuthGateway` operations map cleanly onto `AuthProviderBackend`. Three do
not, and each divergence is a **contract difference**, not a missing feature.

| # | App contract | KMPAuth equivalent | Why it cannot be used verbatim | Resolution |
|---|---|---|---|---|
| D1 | `sendMagicLink(email)` must not create a user for an unknown address — `createUser = false` is set explicitly (`core/auth/SupabaseAuthGateway.kt:83`) | `sendSignInLinkToEmail(email, settings)` → `signInWith(OTP, redirectUrl = …)` with **no** `createUser = false` | KMPAuth's version would create a ghost account for an unknown address, contradicting the comment at `:80-83` and `REQ-UA-014` | Keep the direct SDK call `signInWith(OTP) { email; createUser = false }` in the seam |
| D2 | `verifyMagicLink(email, token)` takes a **raw token** (`core/auth/AuthGateway.kt:66`) | `signInWithEmailLink(email, link, linkAccount)` takes a **full link** and parses `token_hash` / PKCE `code` / implicit `access_token` | The app's link is `singularity://verify?email={email}&token={token}` (`core/auth/AuthLinkSpec.kt:27`) — a `token` parameter KMPAuth's parser does not recognise. Changing the template to `token_hash` is a Supabase-panel change and would invalidate links already in users' inboxes | Keep the direct SDK call `verifyEmailOtp(OtpType.Email.MAGIC_LINK, email, token)` in the seam (`core/auth/SupabaseAuthGateway.kt:92`) |
| D3 | `refresh(accessToken, refreshToken): Result<RemoteSession?>` — an **explicit** refresh token, **both** tokens back, and `null` meaning "rejected" (`core/auth/AuthGateway.kt:68-75`) | `currentUserIdToken(forceRefresh)` → `refreshCurrentSession()` then `currentAccessTokenOrNull()` — refreshes the *stored* session and returns **only the access token** | The app's contract needs both tokens and the null-vs-error distinction that `AuthRepository` depends on (`core/auth/AuthRepository.kt:139-143`) | Keep the direct SDK call `refreshSession(refreshToken)` in the seam, but fix the null-vs-error mapping — see F-12 |
| D4 | `updatePassword(newPassword)` — `auth.updateUser { password = … }` | `reauthenticate(credential)` = a *fresh sign-in*, not a password change | No KMPAuth operation changes a password | Keep the direct SDK call in the seam. `SupabaseAuthBackend.supabaseClient` is public, so the SDK remains reachable from the seam |
| D5 | `signUp` must distinguish "account created, no session because email confirmation is on" from "failed" | `signUp(email, password): Result<KMPAuthUser>` returns a **user** | The app's gateway reads the session back and returns `null` → `auth.no_session` (`core/auth/SupabaseAuthGateway.kt:56`); `AuthViewModel` distinguishes "awaiting confirmation" via `currentSession.value is Session.SignedOut` | Use KMPAuth's `signUp`, then read the session back through the seam — the distinction is preserved |

**Conclusion.** The swap is real but partial: KMPAuth supplies `signUp`, `signIn`, `signOut`,
`currentUser`/`currentUserFlow` and the credential model; the magic-link, OTP-resend,
password-change and explicit-refresh paths stay as direct SDK calls **inside the same seam
file**. The seam remains exactly as confidential as it is today — one file, one
vendor-touching class.

---

## Reproduction

**Not reproduced — and no reproduction was possible or needed.** This is a refactor request,
not a defect report: there is no symptom to reproduce, and the plan does not depend on one.

What was run, and what it printed:

- `git status --short --branch` → clean working tree on `main`; no local modifications.
- `git log --oneline -12` → HEAD is a recent commit; `git branch -vv` shows `main` tracking
  `origin/main`.
- `javap -classpath <auth-kt-api.jar> io.github.jan.supabase.auth.SessionManager` →
  `saveSession(UserSession, Continuation)`, `loadSession(Continuation): UserSession`,
  `loadSessionOrNull`, `deleteSession(Continuation)`.
- `javap … io.github.jan.supabase.auth.AuthConfigDefaults` →
  `getSessionManager` / **`setSessionManager(SessionManager)`**,
  `getAutoLoadFromStorage` / `setAutoLoadFromStorage`,
  `getAutoSaveToStorage` / `setAutoSaveToStorage`,
  `getCheckSessionOnRequest` / `setCheckSessionOnRequest`.
- `javap … io.github.jan.supabase.auth.user.UserSession` → nine-argument data class
  (`accessToken`, `refreshToken`, `providerRefreshToken`, `providerToken`, `expiresIn`,
  `tokenType`, `user`, `type`, `expiresAt`).
- `javap … io.github.jan.supabase.core.auth.AuthProviderBackend` (from the cached
  `kmpauth-core-api.jar`) → the seven-method provider contract listed above.
- `unzip -l <kmpauth-core-api.jar>` → `com/mmk/kmpauth/core/auth/{AuthProviderBackend,
  KMPAuthBackend, AuthCredential, KMPAuthUser, LocalKMPAuthBackend}.class` and
  `com/mmk/kmpauth/core/di/PlatformModule_androidKt`.
- `curl https://repo1.maven.org/maven2/io/github/mirzemehdi/kmpauth-supabase/maven-metadata.xml`
  → `<latest>3.0.6</latest>`, versions `3.0.0-beta01` … `3.0.6`.
- `curl …/kmpauth-supabase-3.0.6.pom` → depends on
  `io.github.jan-tennert.supabase:auth-kt:3.7.0`.
- `curl …/kmpauth-core/maven-metadata.xml` → `<latest>3.0.6</latest>` — the same version
  already in the project.
- `find ~/.gradle -name 'auth-kt-jvm-*.jar'` → `auth-kt-jvm-3.8.0.jar` — the version the
  project actually resolves.
- `grep -n '^supabase\|^kmpauth' gradle/libs.versions.toml` → `supabase = "3.8.0"`,
  `kmpauth = "3.0.6"`.

No build, no test run and no dependency resolution was performed — Plan Mode forbids
mutating the working tree, and a dependency resolution writes into the Gradle cache.

---

## Verified facts

Every claim below carries a `path:line` or a command that was run and observed.

- **F-1** — The auth port declares seven operations returning `Result<T>` over
  `RemoteSession`: `core/auth/AuthGateway.kt:33-76`; `RemoteSession` at `:86-92`.
- **F-2** — The port is the mechanism REQ-UA-008 asks for, and the KDoc says so:
  `core/auth/AuthGateway.kt:18-31`.
- **F-3** — `SupabaseAuthGateway` is the only production implementation of the port:
  `core/auth/AuthGateway.kt:155` is its single call site.
- **F-4** — The seam resolves the client per call rather than injecting the plugin:
  `core/auth/SupabaseAuthGateway.kt:41` (rationale at `:31-40`).
- **F-5** — `sendMagicLink` sets `createUser = false` explicitly, with the reason in the
  comment: `core/auth/SupabaseAuthGateway.kt:78-84`.
- **F-6** — `verifyMagicLink` calls `auth.verifyEmailOtp(OtpType.Email.MAGIC_LINK, email, token)`
  with a raw token: `core/auth/SupabaseAuthGateway.kt:92`.
- **F-7** — The magic-link URI template is `singularity://verify?email={email}&token={token}`:
  `core/auth/AuthLinkSpec.kt:27`.
- **F-8** — `refresh` calls `auth.refreshSession(refreshToken)` and maps a null result to a
  null `RemoteSession`: `core/auth/SupabaseAuthGateway.kt:115-126`.
- **F-9** — `signUp` reads the session back rather than trusting the return value:
  `core/auth/SupabaseAuthGateway.kt:53-56`.
- **F-10** — `SecureSessionStore` writes four keychain values one at a time and undoes a
  partial write: `core/auth/SecureSessionStore.kt:72-105`.
- **F-11** — The plaintext migration ordering is *keychain first, plaintext second*, and a
  failed write must not erase: `core/auth/SecureSessionStore.kt:21-33, 145-160`.
- **F-12** — **The production gateway cannot return `Result.success(null)` from `refresh`.**
  `call()` converts a null block result into a thrown `AppError.Unauthorized(code =
  "auth.no_session")` (`core/auth/SupabaseAuthGateway.kt:135-140`); the inner
  `runCatchingCancellable { auth.refreshSession(refreshToken) }.getOrNull()` collapses both
  a rejection and a transport error to null (`:123-125`); and `runCatchingCancellable`
  rethrows `CancellationException` and catches `Exception`
  (`core/error/RunCatching.kt:23-29`). Therefore every outcome of `refresh` is
  `Result.failure`, and the repository's `onSuccess { session == null }` branch
  (`core/auth/AuthRepository.kt:377-383`) is unreachable with the production gateway —
  it is exercised only by the fake (`shared/src/jvmTest/.../AuthRepositoryTest.kt:331`).
  The consequence is that a **rejected** refresh token is treated as a transport failure:
  the app stays signed in with a dead refresh token, which is what
  `core/auth/AuthRepository.kt:379-381` says it wants to avoid. `REQ-UA-003`'s
  "SHALL move to a signed-out state when the session can no longer be recovered" is
  therefore not satisfied in production.
- **F-13** — The restore path reads four store values and then refreshes:
  `core/auth/AuthRepository.kt:366-397`; the local fallback is `:405-422`.
- **F-14** — The restore is bounded and emits at most one failure event:
  `core/auth/AuthRepository.kt:303-336`; the port's default is `:80`.
- **F-15** — A failed sign-out still signs out locally, and the outbox is never touched:
  `core/auth/AuthRepository.kt:128-137`.
- **F-16** — The local session contacts nothing and the provider never issues an anonymous
  identity: `core/auth/AuthRepository.kt:145-150`,
  `core/auth/AuthGateway.kt:6-16`, ADR
  `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`.
- **F-17** — `DataStoreSessionStore`'s only remaining write is `forgetTokens()`:
  `core/auth/DataStoreSessionStore.kt:155-167`; it also owns the device id and the local
  owner id (`:85-96, 117-125`).
- **F-18** — `Session.SignedIn` carries the id, email, access token and refresh token:
  `core/auth/Session.kt:12`; the account-vs-session comparison is `:17-31`.
- **F-19** — `CurrentUser` is the fan-out point (`core/auth/CurrentUser.kt:28, 47, 51-55`).
- **F-20** — `core/sync` reads `session.accessToken` in 6 places to authenticate requests.
- **F-21** — The vendor-confinement gate allows exactly six files and requires each to
  still import the vendor: `shared/src/jvmTest/.../arch/VendorSdkConfinementTest.kt:136-143`,
  `:92-108`; the positive control is `:110-125`; the scanned prefix is `io.github.jan.supabase`
  (`:134`).
- **F-22** — The DI bindings are: `DataStoreSessionStore` (:195),
  `DeviceIdProvider` → it (:200), `SecureSessionStore` (:202), `AuthGateway` (:217),
  `AuthRepository` (:232), `CurrentUser` (:273) — all in
  `shared/src/commonMain/.../core/di/CoreDiModule.kt`.
- **F-23** — The project uses Koin 4.2.2 and `koin-compiler-plugin` 1.2.1:
  `gradle/libs.versions.toml:41-43`.
- **F-24** — `kmpauth-google:3.0.6` is already a dependency, in `commonMain`:
  `shared/build.gradle.kts:315`, `gradle/libs.versions.toml:83, 271`.
- **F-25** — `kmpauth-supabase:3.0.6` is published and depends on
  `io.github.jan-tennert.supabase:auth-kt:3.7.0` (POM fetched from Maven Central).
- **F-26** — The project pins `supabase = "3.8.0"` (`gradle/libs.versions.toml:79`) and the
  cache holds `auth-kt-jvm-3.8.0.jar`.
- **F-27** — `SupabaseAuthBackend(client: SupabaseClient)` accepts an existing client, and
  `supabaseClient` is exposed publicly — upstream source
  `backends/supabase/kmpauth-supabase/src/commonMain/kotlin/com/mmk/kmpauth/supabase/SupabaseAuthBackend.kt`.
- **F-28** — `SupabaseAuthBackend.sendSignInLinkToEmail` does not set `createUser = false`,
  and `signInWithEmailLink(email, link, …)` parses `token_hash` / PKCE `code` / implicit
  `access_token` — same upstream source.
- **F-29** — `AuthConfigDefaults.sessionManager` is settable, as are
  `autoLoadFromStorage`, `autoSaveToStorage` and `checkSessionOnRequest` —
  `javap` over `auth-kt-jvm-3.8.0.jar`.
- **F-30** — `core/auth` is listed as **not covered** by a capability spec, with candidate
  id `core/auth-token-storage`, and the rule is to author it when a change touches the
  module: `openspec/specs/MODULE-INDEX.md:43` (and `:4-12`).
- **F-31** — `AuthViewModel` distinguishes "awaiting email confirmation" from a failed
  sign-up via `currentSession.value is Session.SignedOut`:
  `shared/src/commonMain/.../feature/auth/AuthViewModel.kt:253`.
- **F-32** — `SecureSessionStore` is also consumed by `TestAuthStubs.installSignedInSession`
  (`shared/src/androidMain/.../test/stubs/TestAuthStubs.kt:125`) and by
  `DebugSeedActivity` (`androidApp/src/debug/.../DebugSeedActivity.kt:384-385`).
- **F-33** — `docs/architecture/bounded-read-inventory.md:98` names
  `core/auth/SecureSessionStore.kt:124-126` as bounded-read sites.
- **F-34** — `UserSession` carries `accessToken`, `refreshToken`, `providerRefreshToken`,
  `providerToken`, `expiresIn`, `tokenType`, `user`, `type`, `expiresAt` —
  `javap` over `auth-kt-jvm-3.8.0.jar`.
- **F-35** — The working tree is clean; `DIGEST.md` does not exist in this checkout (it is
  generated by `./scripts/refresh-decisions-digest.sh`).

---

## Hypotheses

Each with what would confirm and what would refute it.

- **H-1** — `kmpauth-supabase:3.0.6`'s calls into `auth-kt` are binary-compatible with
  `3.8.0`. *Confirm:* the dependency resolves and the gateway's sign-in/sign-up/sign-out
  path runs against the real SDK without a linkage error. *Refute:* a `NoSuchMethodError`
  or `NoClassDefFoundError` at the first SDK call → pin `auth-kt` to `3.7.0`.
- **H-2** — The SDK's `Auth` plugin accepts a custom `SessionManager` and persists the
  session through it. *Confirm:* the `SecureSessionManager` is installed, a sign-in writes
  exactly one keychain entry, and the plain preferences store contains no token.
  *Refute:* the SDK writes to its own `Settings` store regardless → the manager is not
  being used and `REQ-UA-002` is violated.
- **H-3** — `auth.refreshSession(refreshToken)` returns `null` when the refresh token is
  rejected and throws on a transport error. *Confirm:* a stub server answers 400 for the
  refresh and the gateway returns `success(null)`; a dropped connection yields `failure`.
  *Refute:* it throws on rejection too → the seam must classify the vendor exception, and
  the mapping test must assert that classification instead. **The F-12 conclusion is
  robust either way**; only the fix's shape depends on it.
- **H-4** — `SupabaseAuthBackend.currentUserFlow()` emits on every session change, so the
  repository can derive `Session.SignedIn` from it instead of assigning `_currentSession`
  by hand. *Confirm:* the flow emits on sign-in, on refresh and on sign-out. *Refute:* it
  emits only on sign-in/out → keep the manual assignment for refreshes.
- **H-5** — `KMPAuthSupabaseConfiguration` / `SupabaseAuthBackend` do not require the global
  `KMPAuth` facade to be initialised. *Confirm:* the seam constructs `SupabaseAuthBackend`
  directly and every operation works. *Refute:* a missing initialiser throws → the facade
  must be registered, and its Koin glue must then be reconciled with the project's rules.
- **H-6** — The plain preferences store already holds a legacy token on some installs, so
  the migration path has real users. *Confirm:* `DataStoreSessionStore`'s legacy slots are
  non-empty in the field. *Refute:* every install already migrated → the migration is
  defensive only, and its tests are the only evidence.

## Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| **R-1** | **Binary compatibility.** `kmpauth-supabase:3.0.6` depends on `auth-kt:3.7.0`; the app pins `supabase = "3.8.0"` (`gradle/libs.versions.toml:79`) and the cache resolves `auth-kt-jvm-3.8.0`. Gradle takes the higher version, so KMPAuth's pre-compiled calls into the SDK may not bind — a `NoSuchMethodError` at runtime, not at compile time. | **High** | Step 2 of the implementation resolves the dependency and runs the gateway against the real SDK early. If a signature differs, pin `auth-kt` to `3.7.0` and re-check the app's own SDK usage, or upgrade KMPAuth. **This must be settled before the rest of the work.** |
| **R-2** | The SDK's `UserSession` may not be `@Serializable`, or may not round-trip its `expiresAt`. | Medium | The design does not depend on it: a project-level `@Serializable` value is stored, and the seam converts. Verify in step 3. |
| **R-3** | The SDK may auto-save the session to storage in a way that bypasses the app's error handling, so a failed keychain write no longer surfaces as `auth.session_not_stored`. | Medium | `SecureSessionManager.saveSession` owns the failure and rethrows it as `AppError.Persistence(code = "auth.session_not_stored")`. Assert it in `SecureSessionManagerTest`. |
| **R-4** | The SDK's `checkSessionOnRequest` may refresh on its own, changing when `Session.SignedIn` is republished and breaking the account-vs-session comparison at `core/auth/Session.kt:17-31`. | Medium | Decide the flag values explicitly in step 4 and pin them with a test. |
| **R-5** | The magic-link path keeps calling the SDK directly, so "we now use KMPAuth" is true for sign-in/sign-up/sign-out and the credential model, but not for the whole gateway. | Low (expectation) | Stated plainly in the divergence table. Do not claim a full swap in the commit message or the ADR. |
| **R-6** | The KMPAuth Supabase backend is young (3.0.x, `3.0.0` released 2026) and its own issue history is unknown to us. | Low | The app's own `AuthGateway` port isolates it: a defect is fixed in one seam file, and the port can be re-implemented without touching the domain. |
| **R-7** | Scope creep into Google sign-in for the account (option 2), which would need Google Cloud console configuration and a Supabase provider change — both outside this repository. | Low | Explicitly a non-goal. If it is wanted later, it is a separate change with its own spec. |

---

## Open questions

1. **Should the legacy plaintext slots be removed from `DataStoreSessionStore` after the
   migration ships?** The class keeps them as the migration source. Removing them is a
   second, later change (it would need its own migration for the remaining installs).
   *Deferred* — the safe answer is to keep them; the migration is idempotent.
2. **Does the offline/local session eventually move to a provider-side anonymous identity?**
   Out of scope, and ADR `2026-10-05` forbids it. Recorded so a later reader does not
   mistake it for an oversight.
3. **Should `Session.SignedIn` eventually drop its token fields?** It would be the honest
   shape once the SDK owns the session — but it touches `core/sync` and 82 files, and
   `core/auth/Session.kt:17-31` shows the fields are load-bearing for the account-vs-session
   comparison. *Deferred* — deliberately out of scope for this change.

---

## Constraints found

- **Vendor confinement.** `VendorSdkConfinementTest` scans production sources for
  `io.github.jan.supabase` and allows exactly six files (`:136-143`), each of which must
  still import the vendor (`:92-108`). Adding a second vendor means **extending the scanned
  prefix**, not just the allowlist — otherwise the new vendor is unscanned and the rule is
  vacuous for it. The positive control (`:110-125`) must be extended for the new prefix too.
- **One vendor-touching class per file.** The rule at `:32-40`: a seam is a *file*. The
  `SessionManager` implementation is therefore its own file, not a second class inside
  `SupabaseAuthGateway.kt`.
- **Koin 4.x, pure DSL.** No `includes()` (it creates a child scope whose bindings the
  neighbouring modules cannot see — ADR `2026-09-27-di-module-aggregator-narrative`), no
  `*domainModule().toTypedArray()` (KOIN-W003), no `factory { }` for a ViewModel,
  `viewModelOf(::Vm)` for the auth ViewModels. `koin-compiler-plugin 1.2.1` checks the
  graph at compile time. The project's Koin is **4.2.2**; `kmpauth-core` ships its own
  Koin glue, which this change avoids by not using the global `KMPAuth` facade.
- **`java.io.File` is forbidden in `commonMain`** — not implicated here, but the confinement
  test reads the source tree that way (`VendorSdkConfinementTest.kt:4`).
- **`Result<T>` everywhere**; `runCatchingCancellable` rethrows `CancellationException` and
  propagates `Error` (`core/error/RunCatching.kt:23-29`).
- **No `runBlocking` in a ViewModel `init`;** no `*Blocking()` repository methods.
- **The `Session` shape is load-bearing.** `core/auth/Session.kt:17-31` explains why the
  *account* is compared rather than the whole session — a token refresh replaces the
  session wholesale. Reshaping `Session` would ripple into `core/sync` and the 82-file
  fan-out; the plan avoids it.
- **The local session is local.** ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`:
  the provider never issues an anonymous identity, which is why `AuthGateway` has no
  `signInAnonymously` and no attach (`core/auth/AuthGateway.kt:6-16`). KMPAuth's
  `signInAnonymously` must **not** be introduced.
- **Specs.** `openspec/specs/MODULE-INDEX.md:43` lists `core/security`, `core/auth` as not
  covered, with candidate `core/auth-token-storage`, and the rule is to author it when a
  change touches the module. The change directory is `openspec/changes/<slug>/`; the
  pipeline never writes to `specs/NNN-*/`.
- **No iOS.** Android + JVM Desktop only — `kmpauth-supabase` publishes a `jvm` variant, so
  the desktop target is covered.
- **`git fetch` before the first edit;** `sgate checkout-state` to check divergence. Not
  run in this session (Plan Mode: Git state must not change).

---

## References

**Repository**

- `core/auth/AuthGateway.kt` — the port and `RemoteSession`.
- `core/auth/AuthRepository.kt` — the repository, the restore path, the four rules.
- `core/auth/SupabaseAuthGateway.kt` — the vendor seam being replaced.
- `core/auth/SecureSessionStore.kt` — the token store being replaced.
- `core/auth/DataStoreSessionStore.kt` — the legacy plain store; the device id, the local
  owner id, the local-session marker.
- `core/auth/Session.kt`, `core/auth/CurrentUser.kt`, `core/auth/AuthDomain.kt`.
- `core/di/CoreDiModule.kt:190-280` — the auth bindings.
- `core/error/RunCatching.kt:23-29` — `runCatchingCancellable`.
- `core/auth/SupabaseClientProvider.kt` — the client seam.
- `feature/auth/AuthLinkSpec.kt:27` — the magic-link URI template.
- `feature/auth/AuthViewModel.kt:253` — the awaiting-confirmation distinction.
- `feature/auth/LocalAccountClaimer.kt` — the local → account ownership transfer.
- `feature/calendarSync/auth/GoogleConsent.android.kt` — the *existing* KMPAuth use
  (Google Calendar), untouched by this change.
- `shared/src/androidMain/.../test/stubs/TestAuthStubs.kt:125`,
  `androidApp/src/debug/.../DebugSeedActivity.kt:384-385`.
- `shared/src/jvmTest/.../arch/VendorSdkConfinementTest.kt:92-143`,
  `shared/src/jvmTest/.../arch/PlaintextTokenIsolationTest.kt`.
- `shared/src/jvmTest/.../core/auth/{AuthRepositoryTest,AuthTestDoubles,SecureSessionStoreTest}.kt`,
  `shared/src/jvmTest/.../feature/auth/SignOutKeepsLocalDataTest.kt`.

**Specs and decisions**

- `openspec/specs/user-authentication/spec.md` — REQ-UA-001 … REQ-UA-022.
- `openspec/specs/MODULE-INDEX.md:4-12, 43` — the coverage rule and the `core/auth` gap.
- `docs/decisions/2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice.md`.
- `docs/decisions/2026-10-24-google-oauth-kmpauth-signin-server-side-refresh.md` — the
  existing, unrelated KMPAuth decision (Google Calendar).
- `docs/decisions/2026-09-27-di-module-aggregator-narrative.md` — no `includes()`.
- `docs/decisions/2026-09-05-secret-storage-split.md` — the split that produced the
  keychain store.
- `docs/architecture/verification.md`, `docs/architecture/tests.md`,
  `docs/architecture/bounded-read-inventory.md:98`.
- `.specify/templates/engineering/conventions.md` — the pre-read chain, the change
  directory, the status vocabulary.

**Upstream**

- `https://repo1.maven.org/maven2/io/github/mirzemehdi/kmpauth-supabase/maven-metadata.xml`
- `https://repo1.maven.org/maven2/io/github/mirzemehdi/kmpauth-supabase/3.0.6/kmpauth-supabase-3.0.6.pom`
- `https://repo1.maven.org/maven2/io/github/mirzemehdi/kmpauth-core/maven-metadata.xml`
- `https://github.com/mirzemehdi/KMPAuth` — `backends/supabase/kmpauth-supabase/src/commonMain/kotlin/com/mmk/kmpauth/supabase/{SupabaseAuthBackend,KMPAuthSupabaseConfiguration}.kt`
- `https://github.com/mirzemehdi/KMPAuth` — `kmpauth-core` `AuthProviderBackend`,
  `AuthCredential`, `KMPAuthUser`

---
