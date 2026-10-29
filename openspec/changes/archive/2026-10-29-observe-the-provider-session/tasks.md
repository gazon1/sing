# Tasks — Observe the Provider's Session

## Phase 1 — the port and the seam

- [x] T001 Give the identity port a way to report the provider's session as it changes.
  `AuthGateway` gains `fun sessionChanges(): Flow<RemoteSession?>`, and its KDoc says
  what a null means: "the provider holds no session" is an answer, not a failure.

- [x] T002 Read the provider's own status in the seam.
  `SupabaseAuthGateway.sessionChanges()` maps the plugin's status onto the port's type —
  an authenticated status becomes a session, every other status becomes null. The plugin
  is resolved when the flow is collected, and the observation stays with that plugin: a
  session belongs to the client that holds it.

- [x] T003 Give every implementation of the port the new method.
  The stub in the test build answers with an empty flow, the repository's test doubles
  answer with an empty flow or with a flow a test can push into, and the seam answers
  with the provider's own status.

## Phase 2 — the repository

- [x] T004 Republish the session the provider replaced, under the guard the model names.
  `SupabaseAuthRepository` observes the port while it is signed in, and republishes only
  a session with the same owner. A provider without a session, a session for another
  account, and an application that has not published a session yet are all left alone.

- [x] T005 Make a failure to observe non-fatal.
  The observation is an addition to what the application already knows: a provider that
  cannot be observed leaves the restore and the attempts in charge, and the failure is
  reported rather than swallowed.

## Phase 3 — the proof

- [x] T006 Cover the three answers the guard gives.
  `AuthRepositoryTest` holds a case for each: a replaced session is published with the
  same identity, a session for another account is not published, and a provider without a
  session does not sign the user out.

## Phase 4 — the record

- [x] T007 Record the decision.
  `docs/decisions/2026-10-25-the-provider-publishes-its-session.md` says why the port
  grows, why the guard is the model's, why a provider without a session does not sign
  the user out, and why the observation cannot loop.

- [x] T008 Register the change and close the divergence it answers.
  The issue registry gets a record, and the previous change's `results.md` names D-02 as
  the divergence this change closes.

## Gates

- [x] G001 `./gw :shared:compileKotlinJvm :shared:compileTestKotlinJvm
  :shared:compileAndroidMain` — the module compiles.
- [x] G002 The auth suites and the three convention gates pass.
- [x] G003 `just lint-changed` — the changed files pass.
- [x] G004 `just os-validate` — the change directory validates.
- [x] G005 `just tj` — the whole JVM suite passes.
