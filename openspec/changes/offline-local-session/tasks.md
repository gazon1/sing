# Tasks — offline-local-session

Ordered by dependency. Each task is a slice that leaves the project building.

## 1. The local session reaches nothing

- [x] `DataStoreSessionStore`: `auth_local_owner_id` (generated once, never erased by
      sign-out) and `auth_local_session` (the marker, erased by sign-out).
- [x] `AuthRepository.startLocalSession()` replaces `signInAnonymously()`; no gateway
      call, marker written before the session is published, storage failure reported.
- [x] `AuthGateway` / `SupabaseAuthGateway`: `signInAnonymously` and `attachEmail`
      removed — the provider issues no anonymous identity, so there is nothing for
      `updateUser` to attach to.
- [x] `DevAuthRepository`: same shape.

## 2. The session comes back

- [x] `restoreSession()` resumes the local session when there is no token and the marker
      is set; a stored token still outranks it.
- [x] `signOut()` clears the marker and keeps the id.

## 3. The button is reachable

- [x] `LoginScreen`: the offline action is offered on the server-configuration screen,
      carrying the same test tag.
- [x] `AuthIntent.ContinueOffline` replaces `SignInAnonymously`.

## 4. Offline → account, with a choice

- [x] `OwnerReassignDao`: `UPDATE` mirror of `OwnerEraseDao`, one exact `(from, to)`
      equality match per statement; no pattern matching.
- [x] `OwnerRowIdResolver.resolveMapping` → `OwnerIdMapping`, composed by the same
      `scopedUserIdFor` that wrote the ids.
- [x] `OwnerScopedReassigner`: data rows per scope pair, sync state and `profiles` by
      bare owner, profiles **last**.
- [x] `LocalAccountClaimer`: validate → sign in → apply the chosen disposition; a failed
      transfer signs out and restores the local session.
- [x] `AuthIntent.AttachAccount(email, password, disposition)`; `LocalDataDisposition`
      has no default value, so a caller cannot forget to choose.
- [x] `AttachAccountScreen`: shows what the user holds and the two options, unselected,
      with submit disabled until one is picked.

## 5. Tests

- [x] `AuthRepositoryTest`: the local session contacts no provider; leaves no tokens;
      survives a relaunch; a token outranks the marker; sign-out keeps the id; a fresh
      install still shows the login screen; attaching is an ordinary sign-in and a wrong
      password changes nothing.
- [x] `AuthViewModelTest`: `ContinueOffline` with no server configured; a refused local
      session reports and stays; the data summary is published; both dispositions are
      carried; a failed attach keeps the local session and does not navigate.
- [x] `LocalAccountClaimerTest`: keep, discard, wrong password, transfer failure.
- [x] `OwnerReassignDaoTest` (`@Tag("slow")`, real Room): both profiles move, the outbox
      moves, a foreign owner's rows are untouched, and an id that is a suffix of another
      (`eu1` vs profile `u1`) is not confused with it.
- [x] `LoginScreen` / `AttachAccountScreen`: the offline action exists on both screens;
      submit is blocked until the disposition is chosen.

## 6. Gates

- [x] `bash scripts/ci/static-gates.sh`
- [x] `./gradlew :shared:jvmTest -Ptest.tags=fast,slow`
- [x] `just detekt`
- [x] `scripts/find-unwired-surfaces.py`
