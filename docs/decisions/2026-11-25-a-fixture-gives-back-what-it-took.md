---
title: "A fixture gives back what it took from the device"
date: 2026-11-25
status: accepted
tags: [e2e, maestro, tooling]
issue: 3191
---

## Context

The Maestro runner puts the device in portrait before every flow
(`reset_orientation`): a flow that rotates the device and then fails leaves it
rotated, and Maestro keeps the screen size it read before the rotation — so
every visibility check after it is computed against the portrait width, and one
defect is reported as many (measured 2026-11-25, API 36 emulator).

Doing that means writing two system settings: `accelerometer_rotation 0` and
`user_rotation 0`. The runner's device is usually an emulator, and an emulator
has no owner to have a preference — but the runner also runs against a
**physical phone**, and there the first setting is exactly the thing a person
holding the phone chose: rotate-my-screen-on or off. The runner switched it off
and never switched it back.

## Idea

- **A.** Read the settings before the first change and put them back when the
  run is over.
- **B.** Restore them after every flow.
- **C.** Leave it: the runner owns the device for the duration of the run.

## Decision

**A.** A fixture gives back what it took.

- The device's own `accelerometer_rotation` and `user_rotation` are read
  **once per device**, before the first change — after that the settings on the
  device are the fixture's own, and reading them again would remember the
  fixture's values as the device's.
- **Each setting is taken on its own.** A device that answers for one of them
  and not for the other still gets the readable one back; taking the pair as a
  unit would throw the readable one away with the unreadable one and leave the
  device with the fixture's value for both.
- They are put back when the run is over (`restore_orientation`), not after
  every flow: between flows the device has to stay in portrait, because that is
  what the next flow is measured against. The give-back is registered as a
  close callback, after the screen-off callback, so the callbacks (which run in
  reverse order) put the device back while its screen is still awake. The
  orientation is put back before the policy that follows it: the orientation is
  where the device is, the policy is what decides where it may go.
- The give-back is **best-effort**: a device that is gone cannot be given
  anything back, and a setting that cannot be read cannot be given back
  either — that is said once, in a warning, rather than silently.

## Rationale

- **The runner does not own the device between runs.** It borrows it. A setting
  the person holding the phone chose is not the runner's to keep.
- **Reading it once is enough, and reading it again would be wrong.** The
  fixture's own values would be remembered as the device's, and the phone would
  be handed back with the fixture's portrait lock on it — the very thing the
  decision is about.
- **Option B fights the measurement.** Restoring after every flow means the
  next flow starts on a device the previous flow rotated, which is the defect
  the fixture exists to prevent.
- **Option C is right about the run and wrong about the device.** During the
  run the runner does own the device; after it, it does not.

## Consequences

- A physical phone keeps the rotation behaviour its owner chose.
- The orientation fixture is two functions and a module-level map
  (`_TAKEN_ROTATION`), keyed by serial — one device per process.
- The restore is registered unconditionally, and not only under
  `--keep-screen-on`: that flag is about the screen, not about the settings.
- The unit tests for the fixture cover the give-back: the settings are put
  back, the orientation before the policy, they are taken only once, a setting
  that cannot be read still leaves the readable one to be given back, a dead
  device is given nothing, and the give-back happens once.

## Links

- `scripts/src/emulator/maestro/_fixtures.py` — `reset_orientation`,
  `_take_rotation`, `restore_orientation`
- `scripts/src/emulator/maestro/_cli.py` — where the give-back is registered
- `scripts/tests/test_maestro_orientation_fixture.py` — the fixture as a test
- `docs/decisions/2026-10-21-each-flow-gets-a-clean-device.md` — the other half
  of the clean device: the content
- `docs/decisions/2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md`
  — the same rule, applied to the repository
