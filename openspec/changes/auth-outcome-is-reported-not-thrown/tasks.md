# Tasks — auth-outcome-is-reported-not-thrown

Source: `/home/max/Downloads/Тест-план_ авторизация и синхронизация.md` §3.1 (SU-04,
SU-06, SU-13), §3.2 (SI-10), §4 (SS-04).
Spec: `user-authentication`, REQ-UA-009 … REQ-UA-014 (added by this change).

Each task names the test that verifies it. Tests are written first for the tasks that
change a failure path, because the current behaviour is what the plan found wrong and a
test that only follows the new code would not have caught it.

## REQ-UA-009, REQ-UA-010 — normalisation and bounds

- [x] `shared/` Normalise a sign-in address before validating it: trim surrounding
      whitespace and fold letter case. **Test:** an address with padding and uppercase
      letters is accepted; the address that reaches the provider is the folded one.
- [x] `shared/` Bound the accepted length of an address and of a password, and refuse
      input outside those bounds locally, before any network call. **Test:** a 300-character
      address and a 100-character password each fail without the provider being asked.
- [x] `shared/` Fold case the way the provider does, so the client and the server cannot
      disagree about which mailbox an address names. **Test:** a mixed-case address folds to
      lower case, matching the address the provider normalises to.

## REQ-UA-011 — a store failure is a reported failure

- [x] `shared/` Move the session write inside the block whose failure is captured, so a
      failure to store the session arrives as a failed attempt rather than as an exception
      escaping the repository. **Test:** a store that throws makes `signUp` and `signIn`
      return a failure; neither throws.
- [x] `shared/` Report the failure as a local-storage failure with its own code, so it is
      distinguishable from a rejected credential and groups separately in a crash report.
      **Test:** the reported code is the storage one, and it is not the invalid-credential
      one.
- [x] `shared/` End the session at the provider when the device could not store it, so no
      live session is left behind that the device has forgotten. **Test:** a store failure
      during sign-in leaves a sign-out call at the provider.
- [x] `shared/` Say in the message that the attempt reached the provider, because for a
      sign-up the account exists whether or not the session was stored — a retry then
      answers "that address is already registered". **Test:** the message names the local
      failure and does not describe the credentials as wrong.
- [x] `shared/` Clear the pending indicator on this path, as on every other failure.
      **Test:** the indicator is observed true while the store is failing and false after.

## REQ-UA-012 — no partial credential set

- [x] `shared/` Make writing a session all-or-nothing: if any part of the write fails,
      remove what was written so no half-written identity is left in the store. **Test:** a
      write that fails on its third field leaves the store empty, not partly filled.
- [x] `shared/` Report the resulting failure as a persistence failure rather than leaving
      the caller to infer it from a later launch. **Test:** the store reports a classified
      failure, and the cause survives.

## REQ-UA-013 — a plain-text token cannot displace a newer session

- [x] `shared/` Before importing a plain-text token, check whether the secure store already
      holds a session; if it does, treat the plain-text copy as superseded and erase it
      instead of writing it. **Test:** a store that already holds a session plus a leftover
      plain-text token ends with the newer session intact and the plain-text copy gone.
- [x] `shared/` Keep the existing "a failed write does not erase" rule, which is a
      different case: there the secure store is empty, so the plain-text copy is the only
      copy and must be kept. **Test:** an empty secure store plus a plain-text token, with
      the secure write failing, keeps the plain-text token.
- [x] `shared/` Do not let a sign-in that failed to store its session leave the migrated
      token looking like a working one. **Test:** after a failed sign-in the next read does
      not return a session.

## REQ-UA-014 — a session-less sign-up is not a completed sign-in

- [x] `shared/` Represent "the account was created but its address must be confirmed" as
      its own reported outcome, distinct from a completed sign-in and from a failure.
      **Test:** a sign-up that yields no session reports the confirmation outcome; a
      sign-up that fails outright reports a failure, and the two differ.
- [x] `shared/` Stop navigating into the application on that outcome. **Test:** the
      navigation event is not emitted for a session-less sign-up, and is still emitted for
      a completed one.
- [x] `shared/` Tell the user what to do, in the same place the other auth messages go.
      **Test:** the outcome carries a message naming the confirmation step.
- [x] `shared/` Do not run the first-sign-in work on an outcome with no session.
      **Test:** the seed is not triggered by a session-less sign-up.

## Verification

- [ ] `shared/` `./gw :shared:jvmTest` — the new tests and the existing authentication,
      credential-store and view-model tests.
- [ ] `shared/` `./gw detekt` — formatting is by hand; `detekt --auto-correct` is not run.
- [ ] `openspec validate auth-outcome-is-reported-not-thrown --strict`.

## Mutation checks

Each production change was reverted in place and the suite re-run, because a test that
passes both before and after a fix is not testing the fix. All four were caught:

| Reverted | Test that failed |
|---|---|
| the "already superseded" guard in the migration | `a leftover plain-text token does not overwrite a session the keychain already holds` |
| the undo of a partial write | `a failed write is undone for each of the four fields…`, `a launch after a failed write reads no session` |
| moving the session write inside the captured block | `the store failure message says the attempt got as far as the provider`, `the pending flag is cleared after a store failure`, `the session the device could not store is ended at the provider` |
| the session-less branch in the sign-up | `a completed sign-up still enters the application` |

The password-bound test also earned its place the other way round: it failed on first
run because the bound was written in characters while the requirement and the
implementation comment said bytes. `validatePassword` now measures the UTF-8 encoding.

## Related findings, filed rather than fixed here

Ten issues carry the rest of the plan's findings, each with the code evidence. The ones
that lose data silently are #175, #176, #177, #178 and #184; #179 covers a hybrid clock
whose merge half has no caller; #182 is a product question the plan asks as Q1.

