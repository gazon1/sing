---
title: "The capture device is a 9:16 AVD, and the provisioning script gives it the host GPU"
date: 2026-11-17
status: accepted
tags: [android, maestro, testing, release]
---

# The capture device is a 9:16 AVD, and the provisioning script gives it the host GPU

## Context

Google Play's rules for a phone screenshot are short: every side between 320
and 3840 px, and the longest side no more than twice the shortest. The tier
Google recommends for apps is 9:16 portrait from 1080 × 1920 up.

The repository's own emulator is 1080 × 2400 — the `pixel_6` hardware profile
that `scripts/ci/provision-avd.sh` creates by default, and the `Medium_Phone`
AVD on this host. Its longest side is 2400, which is more than twice its
shortest: a screenshot of it is not a screenshot Play accepts.

The obvious workaround, `adb shell wm size 1080x1920`, is the recipe the
RuStore how-to already uses, and it does not work here for two independent
reasons. The override changes the logical resolution and leaves the captured
image at its physical size, so the PNG is still 1080 × 2400 and still
rejected. And the Maestro flows address the screen in percentages, which an
override moves out from under them — the emulator-launch skill forbids it for
that reason.

So the screen has to be 9:16 in the AVD itself. The SDK's hardware profile
table has exactly one phone profile with that shape: `pixel_2`, 1080 × 1920 at
420 dpi. The SDK marks the profile deprecated, and every other phone profile
is either longer than 9:16 or a foldable.

One more thing about that profile: `avdmanager create avd --device pixel_2`
writes `hw.gpu.enabled=no` into the AVD's `config.ini`, and software
rendering is a startup crash on this host — the render thread dies before the
window opens. The launch recipe passes no `-gpu` flag, because the host GPU
is the only mode that boots.

## Idea

Create the capture device from the `pixel_2` profile, and let the
provisioning script write the host GPU into the AVD it just created.

## Decision

**The capture device is a 9:16 AVD, and it is the AVD's own screen.** No
`wm size` override at capture time, and no cropping of a taller capture: the
raw PNG is the source of truth, and a crop of the 1080 × 2400 image would cut
the bottom of the screen off.

**`pixel_2` is the profile, deprecated or not.** It is the only phone profile
in the SDK with a 9:16 screen, and `avdmanager create avd --device pixel_2`
resolves. The capture AVD is named `capture_9x16`.

**The host GPU is the capture device's own ask, not the repository's policy.**
The GPU modes of this repository are split on purpose — the Maestro surface
launches with `-gpu off`, the instrumentation surface with
`-gpu swiftshader_indirect`, and the split is not to be unified before the
performance measurements land (ADR
`2026-10-18-emulator-gpu-strategy-split`). The launch flag wins over the AVD's
configuration, so a global rewrite of `config.ini` would not change those two
surfaces — but it would make the file disagree with them, and a file that says
something the surfaces do not do is a trap for the next person.

So `provision-avd.sh` leaves an AVD's GPU settings alone unless it is asked to
change them, and the capture device asks: `GPU_MODE=host` writes
`hw.gpu.enabled=yes` and `hw.gpu.mode=host` into the AVD's `config.ini`. The
keys are *set* rather than substituted — a profile without them gets them
written, and a profile that cannot be read is a failure rather than a warning,
because "the script reported success" and "the device renders through the host
GPU" have to be the same fact.

## Rationale

The screenshot is the artefact, and its geometry is a hard rule of the
store, not a preference. A capture profile that promises a size the store
rejects is a profile that has to be redone after the first upload, and the
upload happens outside this repository, where nobody sees the reason.

The deprecated profile is a real risk, and it is taken knowingly: the
alternative is a phone screen that Play rejects. If the SDK drops `pixel_2`,
the fallback is an AVD whose `config.ini` carries `hw.lcd.width=1080`,
`hw.lcd.height=1920` and `hw.lcd.density=420` by hand — the same screen, and
the script's GPU line still applies to it.

Putting the GPU fix in the provisioning script rather than in a note is what
makes the device reproducible: the script is what CI and the capture command
both call, and a device that crashes on boot with the reason two steps away
in a log is a device nobody can build twice.

## Consequences

- The capture device is `capture_9x16`, 1080 × 1920 at 420 dpi. The command
  that creates it is in `Maestro/capture/STORYBOARD.md`.
- The capture flows (#3004) and the capture command (#3005) address that
  device. Their percentage coordinates are honest on a 9:16 screen.
- The storyboard says what a 1080 × 1920 frame holds: the task detail shows
  its project one scroll down, and the Upcoming timeline shows three of the
  five seeded days at a time. Both were measured, not guessed.
- `scripts/ci/provision-avd.sh` writes the host GPU into the AVD's
  `config.ini` only when it is asked to (`GPU_MODE=host`); the capture device
  asks, and `scripts/tests/test_provision_avd.py` pins both the asked and the
  untouched cases.
- CI is unchanged: the script leaves an AVD's GPU settings alone unless
  `GPU_MODE=host` is set, and the launch flags of the two CI surfaces win over
  the configuration anyway.

## Links

- `Maestro/capture/STORYBOARD.md` — the capture profile and the device
- `scripts/ci/provision-avd.sh` — the provisioning script
- `docs/decisions/2026-11-17-the-demo-dataset-is-a-seeded-definition.md` — the
  dataset the capture seeds
- `docs/decisions/2026-09-29-emulator-launch-recipe.md` — the launch flags,
  and why the host GPU is the only mode that boots
- <https://support.google.com/googleplay/android-developer/answer/9866151> — the
  preview-asset rules
