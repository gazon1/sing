---
name: singularity-todo-auth-seam
description: 'The auth seam of this KMP app: the AuthGateway port the domain speaks, the Supabase (KMPAuth) binding behind it, the SecureSessionManager that is the one session store, the provider session observation, and the E2E stub that replaces the whole seam in the debug build. Use when touching sign-in/sign-up/sign-out, the session store, the SDK client, the auth repository state machine, or a Maestro auth flow.'
---

> **When to use:** changing anything under `shared/src/*/kotlin/com/singularity/todo/core/auth/`, adding a method to `AuthGateway`, changing how the session is stored or restored, or writing a Maestro flow that drives the auth screen.

## The shape

| Layer | File | What it owns |
|---|---|---|
| Port | `core/auth/AuthGateway.kt` | The identity operations, in the project's vocabulary. The domain sees `RemoteSession` — an id, an email, two token strings and a flag — and never a `UserSession`, `UserInfo` or `SessionStatus`. |
| SDK binding | `core/auth/SupabaseAuthGateway.kt` | The only file that knows the SDK exists. Translates `RemoteSession` ↔ the provider's session. |
| Repository | `core/auth/AuthRepository.kt` | The state machine. Publishes `Session` (`Loading` / `SignedIn` / `SignedOut` / `Anonymous`), owns the restore, owns the refresh, and observes the provider. |
| Store | `core/auth/SecureSessionManager.kt` | The one session store. Encrypts, migrates the old layout, and serialises every write. |
| Client | `core/auth/SupabaseClientProvider.kt` | Builds the SDK client and wires the store into it. |
| Port implementation (E2E) | `test/stubs/TestAuthStubs.kt` | `NoOpAuthGateway` + `StubAuthRepository`, replacing the whole seam in the debug build. |

## Rules that are not obvious

**One session, one copy.** The SDK is given the application's own store
(`sessionManager = sessionStore` in `SupabaseClientProvider`), so there is no second
copy in the plugin's own settings. Leave it that way: a second copy is a second truth
about who the user is.

**The application owns the restore and the refresh.** The client is built with
`autoLoadFromStorage = false` and `alwaysAutoRefresh = false`. A plugin that loads or
refreshes on its own republishes a session the application did not ask for, outside the
restore's deadline and outside the repository's state machine.

**The store serialises its writes.** `SecureSessionManager` holds one mutex and takes
it for every operation on the stored value — the write, the delete and the migration.
"Delete wins" is a decision, not an accident: a sign-out that started while a write was
in flight must not leave a session behind. The flag `migrated` is set **after** the
attempt, so a cancelled migration is finished on the next run.

**The provider publishes; the application republishes.** The provider owns the session
and may replace it behind the application's back (its own refresh, a rotated token, a
session it imported). The port therefore has `sessionChanges(): Flow<RemoteSession?>`,
and the repository observes it — but only republishes when the application is already
signed in **and** the owner is the same. A provider with no session does **not** sign
the user out: the machine has no transition for a session that ended on its own.
ADR `2026-10-25-the-provider-publishes-its-session`.

**The observation is a second reader, not a second source of truth.** It can only
replace the tokens of a session the application already published. A failure to observe
is logged and swallowed — the restore and the attempts stay in charge.

**The port is the test seam.** An `AuthGateway` can be told to fail, to hand back a
*different* user id, or to accept a session it should have rejected, and each of those
is a real event the repository has to survive. Never make the repository depend on an
SDK type to be testable.

## Changing the port

Adding a method to `AuthGateway` means touching **four** places, and the compiler
finds three of them:

1. `SupabaseAuthGateway` — the SDK binding.
2. `NoOpAuthGateway` (`test/stubs/TestAuthStubs.kt`) — the E2E build's replacement.
3. The test doubles: `ScriptedGateway` (`AuthRepositoryTest`), `FakeAuthGateway`
   (`AuthRepositoryRestoreBoundTest`), `UnreachableGateway` (`SignOutKeepsLocalDataTest`).
4. The repository — the state machine decides what the new answer means. A new port
   method is not a new UI state.

`SessionStatus` is the SDK's own vocabulary: `NotAuthenticated` / `Initializing` /
`RefreshFailure` / `Authenticated`. Only `SupabaseAuthGateway` may name it.

## The E2E build

The debug build replaces the seam with `testAuthModule()` (`allowOverride = true`):
no network, a session restored from the application's own encrypted store, and a test
session installed when the store holds none. The app starts signed in.

A flow that needs a *different* stub behaviour drives it through a **flag file** in the
app's own data directory (`files/e2e-stub-auth-<flag>`), written by
`Maestro/helpers/scripts/set-stub-flag.js` through `run-as`. The flags are
`signin_failure`, `signup_failure`, `unconfirmed_signup` and `attach_failure`. The flag
is read on every call, so it takes effect without restarting the app.

A system property is not used for this: the app process has no supported way to read one
(`Runtime.exec("getprop")` is not guaranteed to work from an app, and the supported
route is a hidden API), and the read swallowed its failures — so a flag that read "off"
and a flag that was never set looked the same, and no flow ever proved the channel
worked. ADR `2026-10-25-the-stub-is-driven-by-a-flag-file`.

A flow that types into a field **taps the field first** (`tapOn: {id: …}` then
`inputText: "…"`). Maestro drops the selector from an `inputText` command, so the text
would go to whatever had the focus — usually the field above — and the flow would fail
on the app's behaviour instead of on its own mistake. The selector gate refuses the old
form. ADR `2026-10-25-maestro-drops-the-selector-from-inputtext`.

**Which flows are in the `auth` suite** is decided by what the E2E build can show: a
flow that needs a real provider (a network error) carries `auth-live` and is run by
hand. ADR `2026-10-25-the-auth-suite-is-split-from-the-live-one`.

## Tests

| File | Covers |
|---|---|
| `jvmTest/.../auth/AuthRepositoryTest.kt` | The repository's state machine, including the provider observation. |
| `jvmTest/.../auth/SecureSessionManagerTest.kt` | The store: the write/delete race, the one-time migration, two concurrent reads. |
| `jvmTest/.../auth/SupabaseAuthGatewayTest.kt` | The SDK binding's translation. |
| `jvmTest/.../auth/SupabaseClientProviderTest.kt` | The client wiring — the manager the client carries is the store the application holds. |
| `jvmTest/.../auth/AuthRepositoryStoreFailureTest.kt` | A store that cannot be written. |
| `commonTest/.../auth/AuthRepositoryRestoreBoundTest.kt` | The restore's deadline. |

Run them with `./gw :shared:jvmTest --tests 'com.singularity.todo.core.auth.*'`.

## Decisions

- `2026-10-25-auth-adapter-replaced-by-kmpauth` — the adapter is gone; the SDK owns the session.
- `2026-10-25-the-provider-publishes-its-session` — the observation and its guard.
- `2026-10-25-the-stub-is-driven-by-a-flag-file` — why a flag is a file.
- `2026-10-25-the-auth-suite-is-split-from-the-live-one` — the `auth` / `auth-live` split.
- `2026-10-24-the-session-lives-in-the-client` — why the request carries the user's token.
