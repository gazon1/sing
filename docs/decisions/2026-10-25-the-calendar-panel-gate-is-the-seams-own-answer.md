---
title: "The calendar panel's gate is the seams' own answer"
date: 2026-10-25
status: accepted
tags: [calendar-sync, capability, platform-seams]
---

# The calendar panel's gate is the seams' own answer

## Context

The calendar settings panel used to be offered or hidden by one flag:
`CalendarPermissionRequester.isSupported`. The permission requester answered a question
about a different thing — whether this device may *ask* for the calendar permission — and
the panel took that answer as its own.

That arrangement fell apart in two directions at once.

The flag was the only gate, so it was the only one that had to be right. The permission
requester is a platform port, and its flag was a constant per platform: `true` on Android,
`false` on the desktop. It said nothing about the three seams the panel actually needs —
the provider that talks to the calendar, the settings repository that stores the
preference, and the queries that list the apps to write into — so a platform where any of
those could not do its job would still be offered the panel, and the panel would have
nothing to show.

And then the seams grew flags of their own, each answering for the job it does. The gate
became the conjunction of three flags, and the permission requester's flag lost its
reader: it was still declared, still implemented per platform, and consulted by nobody.
A capability flag with no reader is a promise nobody keeps, and a reader that was moved
without the record being moved with it leaves the old answer standing in the code and in
the prose.

The registry of seams had the same drift. Its row for the work scheduler said the
capability gate was read from `isSupported`, and nothing read it — the row claimed a gate
that did not exist, and the gate test that was supposed to catch that looked for the
symbol anywhere in `commonMain`, so it passed in vain.

## Idea

A gate is a conjunction of the authorities on the capabilities being offered. Each seam
knows what *it* can do here, and the panel needs all of them; a port that answers about
something else is not an authority on the panel, however true its answer is.

And a flag that has no reader is not a capability statement — it is decoration. The fix
is not to keep it in case somebody reads it later; it is to remove it, or to give it the
reader it was always meant for.

## Decision

**The panel's gate is the conjunction of the four seams' own `isSupported` flags, and the
permission requester carries no flag at all.**

The four are the provider, the settings repository, the app queries and the work
scheduler: the panel stores a preference, offers a picker, lists the apps to write into
and runs the sync afterwards, so a platform where any one of the four cannot do its job
must not be offered the panel.

The permission requester keeps `hasPermissions` and `requestPermissions`, which answer
the question they were always about — whether the permission is held, and how to ask for
it — and loses `isSupported`, whose reader had moved.

The scheduler's flag gets the reader the registry had been promising: it is the fourth
conjunct of the gate.

## Rationale

**Each port is the authority on what it does.** The provider knows whether it can talk to
the system calendar, the scheduler knows whether it can schedule the run. Asking a port
about a capability it does not own is how a gate ends up answering a question nobody
asked.

**The flags agreed on every platform the app ships on, so the second gate could only
disagree.** The requester's answer was the conjunction's answer on Android and on the
desktop alike. Two gates that cannot disagree are one gate and a redundant flag — and the
redundant one is the one that goes stale.

**A flag with no reader is worse than no flag.** It reads as a checked fact, it survives
every refactor, and it sends the next reader looking for the gate it used to own. The
registry row for the scheduler said the gate read that flag, and no code did — which is
the shape of the defect this decision removes.

**The registry is a claim about the code, so it has to be true.** The four rows of the
calendar seams now say "four", and the scheduler's row names a flag that the gate really
reads.

## Consequences

- `CalendarPermissionRequester` has no `isSupported`, on either platform. The port's KDoc
  says what replaced it and why.
- The scheduler's flag is read: the gate is four flags, and the registry row is true.
- The permission case is what it always was — `hasPermissions` and a dialog to fix it —
  and it is no longer confused with a platform verdict. A failed read stays a failed
  read: a revoked permission or a sick content provider must not read as "this device
  cannot", because the panel that would have told the two apart is the one that goes
  dark.
- The registry's text is a promise about the code, so a seam whose flag is not read is a
  finding — not a row that quietly outlives its reader.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendarSync/presentation/CalendarSyncViewModel.kt` — the gate.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendarSync/permission/CalendarPermissionRequester.kt` — the port without the flag.
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/calendarSync/presentation/CalendarSyncViewModelCapabilityTest.kt` — the assertions.
- `shared/src/jvmTest/resources/platform-seams.tsv` — the registry's rows.
- `docs/decisions/2026-10-06-a-transient-failure-is-not-a-permanent-one.md` — the record that named the old gate's owner.
