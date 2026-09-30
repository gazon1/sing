---
title: "Inventory of the 'declared but inert' defect class, after verification"
date: 2026-09-30
tags: [testing, audit, detekt, logging, fakes]
status: accepted
follows: 2026-09-30-draft-save-failure-and-testtag-honesty
---

# The "declared but inert" defect class

## Context

Four separate defects in this repo share one shape: something is declared,
documented, or even fully implemented and tested — and nothing connects it to
anything. The class is easy to miss precisely because each piece looks healthy
in isolation.

| # | Defect | Found by |
|---|---|---|
| 1 | `DraftMviViewModel.save()` had no `catch`; a thrown `persist` failed silently | desktop UI flow test |
| 2 | 11 `TestTags` constants applied by no composable | a new guard test |
| 3 | `FileLogWriter` / `LogExporter` never installed, while their KDoc claimed otherwise | implementing a plan step that assumed they were live |
| 4 | `Maestro/flows/agenda/03-saved-views-crud.yaml` waits on a snackbar the screen never shows | checking what was safe to delete |

## What was verified rather than assumed

An inventory was drafted from ADR ledgers and then checked against the code.
Most of it did not survive:

- **`runBlocking` in `CalendarViewModel`** — does not exist. Every remaining
  `runBlocking` is in `KoinBridge` or `SettingsDataStoreMigration`, both
  documented one-shot startup bridges with deliberately non-`runBlocking` names.
- **`SyncBootstrapper.handleDeleted`** — the symbol does not exist.
- **`UserId.anonymous` in commonMain** — all nine occurrences are deliberate
  sentinels with explanatory comments, including one whose entire job is to
  re-stamp `anonymous` on write.
- **`EventBus.tryEmit` swallowing** — it is `Channel.trySend`, which returns a
  result rather than throwing.

The ledger's own warning applies: a `runCatching` grep produces mostly false
positives. Each item was confirmed by reading the call chain, and the ones that
did not hold were struck rather than filed.

## The one that was real underneath

`UserId.anonymous` and `UserId("test-user")` were both in use as the *default*
current user, in the same test-fake package: `FakeSettingsRepository` and
`FakeProfileAwareCurrentUser` defaulted to `test-user`, while `testTask()` and
`testNote()` stamped fixtures with `anonymous`. Nothing failed loudly, because
the write path re-stamps the id — so a fixture was visible through one fake and
not another, and a test only noticed if it needed both.

That is precisely what `FakeRepositoryFidelityTest` exists to catch: a fake
reproducing a *different* contract from production rather than the real one.

## Decision

`TestUsers.DEFAULT` — one id that every fake and fixture defaults to. Tests that
need two users pass both explicitly; the point is that the *unspecified* case is
unambiguous.

`SwallowedException` enabled in detekt, with a positive control verified before
enabling: the rule fires on a deliberate violation, and reports zero findings
against the real code. It is the mechanical guard for defect #1's class — a
caught exception that is neither logged nor rethrown. The coroutines ruleset was
considered and rejected; its heuristics produced more noise than signal.

## Consequences

- Two latent fake-contract divergences are now impossible by construction.
- The KDoc on `LogBootstrap` no longer promises disk logging that does not
  happen; whether the writers should be wired or deleted is deferred as a
  product call, since a 20 MB rolling writer on every device is a retention and
  battery decision.
- `find-unwired-surfaces.py` recognises four shapes — screen, default-noop,
  di-binding, navigation — and none of them is "implemented and tested but never
  wired", which is how `FileLogWriter` survived. A fifth shape is the natural
  companion fix.
- The stale Maestro flow is deferred rather than fixed here: it needs an emulator
  to verify, and the fix depends on whether "Saved" is meant to be a toast or a
  dialog.

## Open

- Should `FileLogWriter` be installed, or deleted with its test? Product call.
- Is `SavedAgendaScreen`'s "Saved" feedback meant to be a snackbar (the flow's
  comment says so) or a dialog (what the code does)?
- The NoDate question is separate and still open; see
  `2026-09-30-nodate-root-cause`.

## Links

- `deferred-backlog.md`
- `2026-09-30-draft-save-failure-and-testtag-honesty.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/TestUsers.kt`
