---
title: maestro-date-js-host-clock
date: 2026-09-29
status: accepted
tags: [maestro, test-infrastructure]
authors: Singularity Developer
---

# Context

The Maestro test suite uses `Maestro/helpers/scripts/date.js` to dynamically substitute
calendar month and day references in `calendar/01-month-view.yaml`,
`calendar/02-view-mode.yaml`, and `calendar/03-tap-day.yaml`. The script reads
`new Date()` on the **host machine** (where the Maestro CLI runs), not the Android
emulator's system clock.

# Idea

Inject the current month name (`"September 2026"`), last day of month (`"30"`),
and day-15 title (`"September 15, 2026"`) from host JavaScript into the Maestro
flow YAML at runtime, so the calendar flows do not break on 2026-10-01.

# Decision

Use `runScript: ../../helpers/scripts/date.js` in each calendar flow. The script
outputs `${output.monthTitle}`, `${output.lastDay}`, and `${output.day15Title}`
which are interpolated into `visible:` and `assertVisible:` selectors.

The host machine is the only clock accessible to the Maestro CLI without an `adb shell`
round-trip per flow. The emulator syncs via NTP; the host clock is assumed to be
correct.

# Rationale

- `runScript` is a first-class Maestro command; no additional tooling needed.
- The script is ES5-compatible (no arrow functions, `const`/`let`, or template literals)
  to work with the Rhino JS engine embedded in the Maestro CLI.
- The alternative — `adb shell date` per flow — adds latency and is fragile if the
  emulator is slow to respond.

# Consequences

- **Known gap**: if the host and emulator clocks diverge (NTP lag, timezone offset,
  emulator wall-clock frozen at a different time), the flow will wait for a month
  the calendar is not showing. The script comment documents this; the runner's
  `ensure-emulator.sh` assumes the emulator syncs NTP shortly after boot.
- **Weekday abbreviations** (`"Mon"`, `"Sun"`) and mode labels (`"Month"`, `"Week"`)
  are still hardcoded English in calendar flows and are **not** substituted by
  `date.js`. These must be addressed by pinning the emulator locale to `en-US`
  (see `scripts/run-maestro.sh` locale-pinning step in PR3 CONVENTIONS.md).
- A more robust solution would pass the emulator clock through `adb shell` into the
  flow environment, but that is deferred to a future iteration.

# Links

- `Maestro/helpers/scripts/date.js`
- `Maestro/flows/calendar/01-month-view.yaml`
- `Maestro/flows/calendar/02-view-mode.yaml`
- `Maestro/flows/calendar/03-tap-day.yaml`
