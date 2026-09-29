---
title: "Emulator launch recipe — windowed, hardware GPU, camera and audio off"
date: 2026-09-29
status: accepted
tags: [emulator, android, tooling]
---

## Context

An agent session needed the emulator for Maestro smoke flows and failed to
launch it four times before finding a working configuration. Three failures
were self-inflicted (flags that the existing ADR already ruled out); the fourth
exposed a genuinely new crash.

* **`-gpu swiftshader_indirect`, `-gpu off`, `-no-window`** — all SIGSEGV on
  this host. `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md`
  documents exactly this in its dead-ends table; the flags were retried anyway
  because the ADR's failure signature (`amdgpu CS rejected`) did not match what
  was being debugged. The ADR's *resolution* row (default hardware GPU works)
  is the part that generalizes.
* **Background-process survival** — a plain `&` child dies when the tool
  harness's shell exits, and `pgrep -f "emulator"` matches the invoking shell,
  reporting a live emulator when there is none. A background task can also be
  reported "failed" while qemu itself survives the wrapper's exit.
* **New crash: the emulator died ~9 minutes into a session** with `SIGABRT`,
  crash thread in `libandroid-webrtc.so` (`event_engine` frames in the stack) —
  the virtual camera's WebRTC stack. Distinguished from the Mesa crash by
  uptime and by the crashing library; no GPU error appears anywhere.

## Idea

1. **Windowed + default GPU + camera/audio disabled.** Keep what the Mesa ADR
   proved works, and remove the camera, whose WebRTC thread is the new crash
   source. `-no-audio` removes an unrelated host-audio failure mode cheaply.
2. **Headless or software rendering with retries.** Rejected outright — both
   are in the proven dead-ends table.

## Decision

The launch recipe is fixed as:

```bash
emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim \
  -camera-back none -camera-front none -no-audio > /tmp/emu.log 2>&1 &
```

launched from a shell call that stays alive a few seconds, then verified by a
`/proc/*/exe` scan (not `pgrep -f`), then awaited via
`adb shell getprop sys.boot_completed`. Full procedure, including lock cleanup
after a crash and crash triage by signature:
the `singularity-todo-emulator-launch` skill.

## Rationale

The camera flags are the only non-obvious part. The evidence: uptime ~533 s,
`SIGABRT` (not `SIGSEGV`), thread 76 in `libandroid-webrtc.so` with
`event_engine` in the stack — the guest camera stack, not the GPU. With
`-camera-back none -camera-front none` the emulator survived multiple full
smoke-suite runs back to back. `-no-audio` is cheap insurance with no observed
downside.

Windowed-with-hardware-GPU is kept because both alternatives are in the Mesa
ADR's dead-ends table; the emulator window is expected to be visible on the
desktop, and "I don't see the emulator" means it was launched headless.

## Consequences

- Launch the emulator only via the recipe in `singularity-todo-emulator-launch`;
  never add `-gpu`/`-no-window` flags to "fix" a startup failure, and never run
  long sessions without the camera disabled.
- Aliveness is determined by the `/proc` exe scan and `adb devices`; `pgrep -f`
  output and background-task status notifications are not evidence.
- After any crash: `pkill -f qemu-system-x86_64` and remove
  `~/.android/avd/Medium_Phone.avd/*.lock` before relaunching.
- The `android-emulator` MCP plugin's `android_start_emulator` times out on
  this host; its other tools (screenshot, UI inspect) work against an emulator
  started by the direct command.

## Links

- `.agents/skills/singularity-todo-emulator-launch/SKILL.md` — the operational recipe
- `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md` — GPU dead ends (in force)
- `scripts/run-maestro.sh` — device prep steps the recipe complements
