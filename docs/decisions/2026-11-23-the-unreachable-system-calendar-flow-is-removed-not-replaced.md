---
title: "The unreachable system-calendar flow is removed, and the guarantee stays where it can be reached"
date: 2026-11-23
status: accepted
tags: ["testing", "maestro", "traceability", "calendar-sync"]
---

# The unreachable system-calendar flow is removed, and the guarantee stays where it can be reached

## Context

`Maestro/flows/calendar_sync/02-system-calendar-unavailable.yaml` asserted that the
calendar-sync panel shows `calendar_sync_system_unavailable` — the message that says
the platform cannot reach a system calendar. It failed in every sampled full run, with
the cause `Assertion is false: id: calendar_sync_system_unavailable is visible`
(issue #835).

The audit that produced `docs/testing/maestro-guarantees-inventory.md` named the cause
as "a desktop scenario wearing an Android costume", and the reason is in the
production code. `CalendarSyncViewModel` computes

```kotlin
val deviceSyncSupported = calendarProvider.isSupported && syncRepo.isSupported &&
    appQueries.isSupported && scheduler.isSupported
```

and the panel renders the unavailable message only when that conjunction is false.
On Android all four seams are real implementations; only the JVM actuals set
`isSupported = false` (`NoopCalendarProvider`, `JvmCalendarAppQueries`,
`NoopCalendarSyncWorkScheduler`). The gate order in `SystemCalendarPanel` is
`!isSupported` → unavailable message, `!hasPermissions` → permission gate, else the
controls. So no Android device can reach the message the flow asserted.

Meanwhile the *same* flow family carried a second defect: flow 03 carried the bare
tag `CAL-SYNC-SYSTEM-01` instead of `scenario:CAL-SYNC-SYSTEM-02`, so the scenario it
actually verifies had no resolved carrier. `CAL-SYNC-SYSTEM-02` was a dark scenario
and `CAL-SYNC-SYSTEM-02/android` a hole, while the matrix showed a `●` for a cell the
bare tag never resolved.

## Idea

- Delete flow 02 rather than replace it: the guarantee it names is a desktop
  guarantee, and the desktop tier already owns it in
  `CalendarSyncSystemScenarioTest.the_system_panel_says_why_it_cannot_work` (exact
  wording, no enable switch) plus `CalendarSyncPlatformHonestyTest`.
- Do not build a permission-gate replacement flow in the same change. It needs a new
  test tag, Maestro permission control, a new scenario spec, and a device run — and
  the one thing this issue is about is an assertion that no device can satisfy. An
  unverified replacement flow would reproduce the failure mode.
- Fix flow 03's tag so the scenario it verifies has a carrier, and keep the flow body
  byte-identical so the flow cannot start failing because of a metadata change.
- Withdraw the Android claim from `CAL-SYNC-SYSTEM-01` instead of keeping it as an
  `unreachable` cell: a state no platform can reach is not a guarantee to be covered.

## Decision

- `Maestro/flows/calendar_sync/02-system-calendar-unavailable.yaml` is deleted, and
  the catalogue, the qualification frozen set, the qualification baseline and the
  pinned digests in `scripts/check-maestro-flow-qualification.py` lose it in the same
  change.
- `Maestro/flows/calendar_sync/03-system-calendar-toggle.yaml` carries
  `scenario:CAL-SYNC-SYSTEM-02`; its steps, selectors and assertions are unchanged,
  so it is admitted by a qualification record in
  `config/docs/maestro-flow-qualification.yaml`: three consecutive passes on a real
  device (realme RMX3938, API 35, serial 0O65321F32204DD5, Maestro 2.10.0), run
  against the APK built from this checkout.
- `CAL-SYNC-SYSTEM-01` claims `desktop` only. Its spec comment now says why the
  Android branch cannot be reached, and names the permission gate as the reachable
  Android state this scenario does not cover and that no carrier owns yet.
- The debt cause #835 becomes `owner: test`, `status: fixed`: the message appearing
  on Android is now a regression, not an accepted state.
- The ratchet floors drop to 69 holes, 51 dark scenarios, 7 unreachable cells.

## Rationale

**Deleting is the honest disposition.** A replacement flow would have to assert
something reachable; the only reachable thing left is the permission gate, and that
is a different guarantee (missing permission, not a missing platform) owned by a
different code path. Renaming the assertion without changing what it means would
have kept a flow that cannot pass.

**The guarantee does not disappear with the flow.** The panel message is a real
product behaviour on desktop, and the desktop test asserts it with its exact wording.
Deleting the Android flow removes a claim about Android, not the behaviour.

**Withdrawing the claim beats marking it unreachable.** The `unreachable` flag says
"this tier cannot get there, so the cell is excused". It fits a scenario whose
*content* is unreachable on that tier. Here the scenario's expected text is the
Android-reachable message, so no Android target exists at all — keeping the cell
would represent a platform state that does not exist as a required product behaviour.

**The broken link was the more expensive half of the defect.** Flow 03 had been
passing and was verifying a real Android guarantee; it was invisible to the matrix
because its tag lacked the `scenario:` prefix. Fixing it moves a dark scenario to a
carrier without touching the flow's behaviour.

## Consequences

- The `full` Maestro suite has one flow fewer (126 automated flows).
- `CAL-SYNC-SYSTEM-02/android` is automated for the first time; the matrix prints `●`
  for a flow that has been passing all along.
- Flow 03 is qualified by three consecutive device passes, recorded in
  `config/docs/maestro-flow-qualification.yaml` (2026-11-23). The record pins the
  fingerprint of the file in the tree, so the next edit to that flow's body needs a
  new qualification.
- An earlier run of the same three attempts on the same device died once: the
  Maestro server on the phone crashed before the flow started (`DeviceServerDiedException`
  at `deviceInfo`, 1 s into the attempt), because the phone's screen had gone to sleep
  during the install. The device was woken and held awake for the run that produced
  the record. It is a device-side failure, not a product failure, and it is kept in
  the run log rather than erased.
- The Android permission gate (READ/WRITE_CALENDAR not granted) is a reachable state
  with no test tag and no carrier. It is named in the `CAL-SYNC-SYSTEM-01` spec; it
  is not covered here.

## Links

- gazon1/sing#3115, gazon1/sing#835
- `docs/testing/maestro-guarantees-inventory.md` (Appendix A)
- `shared/src/commonMain/.../feature/calendarSync/presentation/CalendarSyncViewModel.kt`,
  `CalendarSyncSettingsScreen.kt`
- `desktopApp/src/jvmTest/.../feature/flows/calendar/CalendarSyncSystemScenarioTest.kt`
- `scripts/src/traceability/scenarios/calendar/sync/system/CAL-SYNC-SYSTEM-01.yaml`
