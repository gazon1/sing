---
title: "An end-to-end flow asserts on structure, not on the app's copy"
date: 2026-10-20
status: accepted
tags: [maestro, e2e, testing]
---

## Context

The nightly Maestro run is red on a large part of its calendar and auth flows.
Two things are going on, and they are separate:

- The **calendar** flows failed because the cleanup that was supposed to remove
  the previous flow's fixtures never matched (`2026-10-20-the-maestro-cleanup-never-matched.md`).
  The locale was not the cause: the app's month title comes from
  `Month.displayName()`, which returns the English enum name, so a Russian
  locale renders "October 2026" exactly as before.
- The **auth** flows failed on `settings_tab_account` — the Account tab is the
  last of twelve rows in a scrollable rail, so waiting for it without
  scrolling waits for something off-screen.

Underneath both is the same shape. The flows assert on **text**: "October
2026", "October 15, 2026", "Month", "Today", "OK". Every one of those is a
string the app may translate, and the emulator in CI runs with
`persist.sys.locale=ru-RU`. A flow that asserts on the app's copy passes on
the developer's machine, in English, and fails in CI, in Russian — and the
failure reads as "the app is broken" rather than "the test names a string".

The auth flows had a second problem of the same family: they expected a
**signed-in** app, and got one. A previous flow in the same run had signed in,
so the app was already authenticated, and the flow's own sign-in had nothing
to do. The flows cleared the state (`clearState: true`) to fix it, which
wipes the device — including the app's data — and takes the seconds that
makes the suite slow.

## Idea

- **A.** Keep the text assertions, and set the emulator's locale to English in
  CI. One line, and the copy becomes stable again.
- **B.** Replace the text assertions with structural ones — test tags, content
  descriptions, numeric cells — and give the auth flows a signed-out app
  without clearing the device.
- **C.** Fix only the flows that are currently red, and leave the rest.

## Decision

**B.**

- **The flows address elements, not strings.** The calendar helpers address a
  day cell by a tag that carries the date (`calendar_day_YYYY_MM_DD`), and
  the mode control is addressed by `calendar_view_mode_trigger` instead of the
  word "Month". The Material3 date picker's cells are addressed by the day
  number as a standalone number, which matches both the English and the
  Russian content description.
- **The app is signed out for the auth flows, and the reset is a session
  reset, not a wipe.** The debug deep link `todo-debug://seed?session=signedout`
  calls `AuthRepository.signOut()` — the same call the app's own button makes.
  The runner performs that reset before every flow, and the flows no longer
  use `clearState: true`.
- **Two app-side tags are still missing** — the month title and the day-view
  header cell. The flows carry a comment naming them, so the remaining text
  assertions are visible as a debt rather than as a convention.
- **The Account tab is scrolled to**, because it is the last of twelve rows in
  a scrollable rail and waiting for it without scrolling waits for something
  off-screen.

## Rationale

- **A makes the failure mode permanent and invisible.** The emulator's locale
  is a property of the *run*, not of the *app*: the app has one behaviour
  under any locale, and a test that only ever sees one of them is not
  testing the app. Pinning the locale to English turns a real defect class
  (a translated string the flow cannot find) into a run that only passes
  because the environment was set up to avoid it — and the developer's
  Russian phone still breaks the flow.
- **A translated string is not a stable identity.** The tag is. A tag says
  "this is the day cell for 15 October"; the text says "October 15, 2026"
  today and something else in the next translation. The number of assertions
  that survive a translation is the number that address structure.
- **The sign-out is what the auth flows are about, so it has to be the
  precondition.** A flow that asserts on the sign-in screen while the app is
  already signed in is testing nothing: the screen it expects is not there,
  and the failure says "the app is broken" instead of "the app was in the
  wrong state". The state has to be *arranged*, and the arrangement has to be
  the one the flow is about — a signed-out app, not a wiped device.
- **A wipe is a bigger hammer than the problem.** `clearState: true` clears
  the app's data on the device, which is not what the flow needs and is slow.
  The session reset is one call, and it is the call the app's own Account
  screen makes.
- **C leaves the defect class in place.** The flows that are not red today
  are not red because they are better; they are red later, when a translation
  lands, and they will be red in a run nobody is watching.

## Consequences

- **The suite no longer depends on the emulator's locale** for the flows it
  has been converted. The two remaining text assertions are named in the
  flows' comments.
- **The auth flows start from a signed-out app**, which is their subject.
  Two of them (`auth/03`, `auth/04`, `auth/05`) assert the reachable outcome
  rather than "lands on the agenda", because the E2E build's auth stub
  answers a successful sign-in without establishing a session — a stub
  limitation, recorded in the flows and in the stub's own file.
- **`auth/08`, `auth/09` and the backup flow still use `clearState: true`.**
  They are the real-server paths and the backup path, and they are outside
  this change. The convention now says the blanket wipe is not the default.
- **`auth/12` has a third, independent problem** that this change does not
  fix: the stub replaces the repository that writes the encrypted store, so
  there is no file to damage, and the scripts shell out to `run-as` as if
  they ran on the device.
- **The runner resets the session before every flow**, which is a process
  reset. With a real (non-stub) auth repository the session would live in the
  store and the reset would not restore it — which is true today only because
  the E2E build always uses the stub.

## Links

- `Maestro/helpers/calendar-day-cell.yaml` — a day cell addressed by tag
- `Maestro/helpers/session-signedout.yaml` — the signed-out app
- `Maestro/CONVENTIONS.md` — the rules this change states
- `scripts/src/emulator/maestro/_fixtures.py` — `reset_session()`
- `2026-10-20-the-maestro-cleanup-never-matched.md` — the other cause of the
  red calendar flows
