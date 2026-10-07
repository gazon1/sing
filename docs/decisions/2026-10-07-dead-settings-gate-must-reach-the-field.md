---
date: 2026-10-07
status: accepted
slug: dead-settings-gate-must-reach-the-field
---

# A gate is only as deep as the layer it stops at

## Context

`scripts/check-dead-settings.py` landed on `origin/main` at 11:40 today as a
blocking gate for a class of defect it was written to prevent: a setting that is
persisted, rendered in the Settings UI, and read by no feature.

Two hours later it was still green, and the one known live instance of its own
class — `Notifications.reminderDefault` — was still dead. The gate passed on the
exact bug it existed for.

Worse, the sweep behind it found more. Of 31 fields on `SettingsBundle`, twelve had
no production consumer outside the settings plumbing. Six of those are the
work-schedule group: `dayStartMinutes`, `dayEndMinutes`, `lunchStartMinutes`,
`lunchEndMinutes`, `weekendSat`, `weekendSun` — a complete day-boundary UI (work
day, lunch window, weekend days) that no agenda, query or selector reads. Two more
are the greeting hour boundaries. And `Notifications.sound` / `vibration` are
read by nothing at fire time: `AndroidNotifier.post(tag, title, body, viewId)`
takes no sound argument and never calls `setDefaults`/`setSound`/`setVibrate`.

So the gate did not merely miss its subject. It was structurally incapable of
seeing it.

## The three wrong layers

The gate was rewritten three times, and each revision failed the same way — by
stopping one layer too early. Recording them because the pattern is the lesson.

**Revision 1 — matched the preference key.** `REMINDER_DEFAULT` is declared in
`SettingsRepository`, so the scan found it "read" there. It is: the repository
builds a `Flow` over the same `Preferences` map it writes to. A key is always read
by the thing that writes it. This is a tautology, not a consumption, and it
reported 40+ false positives.

**Revision 2 — followed `XSettingsStore` to `XSettingsContributor`, excluding the DI
layer.** Reported nothing at all. `CoreDiModule.kt:403` resolves the contributor via
`getOrNull<NotificationsContributor>()`; excluding the DI layer removes the only
place consumption can be observed.

**Revision 3 — counted `getOrNull<…Contributor>()` as the proof of use.** This was
correct about *wiring* and wrong about *use*. A section can be wired into the
SettingsViewModel and still read by nothing outside `feature/settings/`. That is the
precise shape of this bug — the UI renders every field unconditionally — so
counting it as a consumer guaranteed a green gate on every dead field.

Revision 3 is the dangerous one. It passed `check-gate-wiring.py`'s positive
control, because its control mutated a DI binding and revision 3 could indeed see
unwired sections. The control and the check agreed, and both were measuring the
wrong thing.

## Idea

Stop the walk where the value stops moving.

Every surviving path from a preference key to a feature crosses the same set of
directories, and every one of them can only pass a value along:

- `core/settings/` — declares keys, builds flows, writes.
- `core/<domain>/` — `XSettingsStore` and `XSettingsContributor` read a field in
  order to hand it to the next layer.
- `feature/settings/` — renders and writes every section unconditionally.
- `core/di/` — resolves the contributor into the SettingsViewModel.

None of these can *act* on a value. Consumption is a feature reading it, so the
check is whether any file outside that plumbing names the field.

## Decision

Descend to the individual field.

A persisted field is dead when no file outside the settings plumbing names it.
`EphemeralState` is excluded explicitly, by the interface its holders implement —
`isExporting`, `exportedPath` and `savedViews` are UI state, and calling them dead
settings would be a finding about a different concept.

Exemptions moved from a bare comment to `scripts/check-dead-settings-baseline.txt`,
one `Section.field` per line with a reason. A line is a claim that a human looked
and chose; the reason is what distinguishes a decision from an oversight.

The positive control was replaced, not added to. It now removes a baseline entry,
which is the mutation the check actually exists to catch. Mutating a DI binding —
what revision 3's control did — proved only that an unwired section is visible.

## Rationale

**The control has to descend as far as the check.** This is the whole content of the
failure. `check-gate-wiring.py` Part F requires a positive control, and it accepted
one that was measuring the wrong layer, because a control and a check that agree on
the wrong question look exactly like a control and a check that agree on the right
one. A gate whose control cannot fail the case it was written for is worse than no
gate: it converts "not checked" into "checked and green".

**`EphemeralState` is a real exclusion, not a convenience.** Three of the twelve
fields are UI state. Including them would produce findings that look legitimate and
mean nothing, and the first person to hit that would stop reading the output.

**The baseline is the product decision, made visible.** None of the eleven fields is
being fixed here — the honest move is to implement the day-boundary concept or hide
its screen, and that is not a technical call. Recording each field with the reason
keeps the decision greppable instead of leaving it as an absence somebody re-derives
in six months.

## Consequences

- The gate now fails on eleven fields until they are baselined. They are baselined in
  this change, each with the reason, so the gate goes green while the decisions stay
  visible.
- `Notifications.sound` / `vibration` are the most actionable of the eleven:
  `AndroidNotifier.post` is one parameter away from honouring them, which makes them
  a missing feature rather than an absent one.
- Any future settings field added to a `SettingsSection` is subject to the rule from
  the commit that adds it. Writing it into the baseline with a reason is the friction
  that is intended.
- `PlatformSeamGuardTest`'s `gate` column and this file are the same idea at two
  layers: declare what must be true, then fail when it is not. The seam registry
  checks that a capability is read; this one checks that a value reaches a consumer.

## Links

- `scripts/check-dead-settings.py`
- `scripts/check-dead-settings-baseline.txt`
- `scripts/check-gate-wiring.py` (`SCRIPT_GATES`, entry `dead-settings`)
- `scripts/ci/static-gates.sh`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsBundle.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/core/notifications/AndroidNotifier.kt`
- Same series: `2026-10-07-reminders-capability-gate.md`,
  `2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`
