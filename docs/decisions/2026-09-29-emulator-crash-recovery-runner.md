---
title: "Emulator gfxstream crash — the IME mitigation is insufficient, recover instead of prevent"
date: 2026-09-29
status: accepted
tags: [emulator, android, maestro, tooling]
---

## Context

The emulator on this host died repeatedly during the Maestro suite, and the
existing ADR attributed it to the soft keyboard, with
`settings put secure show_ime_with_hard_keyboard 0` as the mitigation. That ADR
was honest about its own gap: no control run with the IME enabled was made, and
causality rested on the coredump stack plus time correlation.

`coredumpctl` settles the mechanism. Every recent crash has the same stack:

```
#0  __strlen_avx2 (libc.so.6)
#1  gfxstream::host::gl::TextureResize::TextureResize(unsigned, unsigned)
#2  gfxstream::host::gl::ColorBufferGl::create(...)
#3  gfxstream::host::gl::EmulationGl::createColorBuffer(...)
#4  gfxstream::host::ColorBuffer::Impl::create(...)
#6  gfxstream::host::FrameBuffer::Impl::createColorBufferWithResourceHandleLocked(...)
#7  gfxstream::host::FrameBuffer::Impl::createColorBuffer(int, int)
#8  gfxstream::renderControl_decoder_context_t::decode(...)
#9  gfxstream::host::RenderThread::main()
```

The guest asks the host for a new ColorBuffer every time a new surface appears,
and `TextureResize` dereferences a bad pointer while creating it. The older
`SIGABRT` in `libandroid-webrtc.so` was a separate fault, fixed by
`-camera-* none`.

## The control run the previous ADR did not do

With the soft IME disabled outright —
`ime disable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`
— the crash still reproduced, with an identical stack, on the first run. So the
IME is **a** trigger, not **the** trigger. Any surface creation does it: a
bottom sheet, a dialog, a navigation push, a snackbar. The earlier attribution
was over-specific, and `show_ime_with_hard_keyboard 0` is a partial mitigation
at best.

Renderer alternatives were tested and all fail on this API 36 AVD:

| Config | Result |
|---|---|
| `-gpu auto` (default, host GPU) | Boots in ~12 s, crashes on ColorBuffer creation |
| `-gpu software` | Process exits during start-up |
| `-gpu swangle` | Process exits during start-up |
| `-no-window` | SIGSEGV before the window is created |

There is therefore no configuration that both boots and avoids the crash. It is
a host-emulator bug, and this project cannot fix it.

## Decision

Stop trying to prevent it; make a lost device recoverable.

**`scripts/ensure-emulator.sh`** (new) — prints a ready serial, or starts the
AVD with the verified flags, waits for boot, and prints the serial. It clears
stale AVD locks first, since a crashed run leaves the AVD locked and a second
qemu refuses to start.

**`scripts/run-maestro.sh`** (rewritten) — runs one flow per `maestro test`
invocation instead of one batched call, and on a lost device relaunches the AVD,
reinstalls, and retries that flow once (`MAESTRO_MAX_RETRIES`, default 1).

Two details that only showed up under test:

- **Batching is what made this look catastrophic.** One `maestro test a.yaml
  b.yaml c.yaml` run means a single crash fails every later flow in ~10 ms
  because no device is left, which reads as a wall of selector failures. One
  invocation per flow keeps the blast radius to one flow.
- **`--include-tags` is ignored when a single file is passed.** Under the old
  batched call it worked; per-flow it silently stopped filtering and ran all 35
  flows under `TAGS=smoke`. The runner now filters the file list itself by
  parsing each flow's `tags:` header.
- **`adb get-state` is not a liveness check.** After the render thread dies,
  adb keeps reporting the serial as `device` for a while before flipping to
  `offline`, so a failure looked like a genuine assertion failure and the
  recovery never triggered. The check now also requires a `shell echo` round
  trip.

## Rationale

Prevention was already exhausted: the bug is in the emulator's render thread,
every alternative renderer fails to boot, and the one working configuration
crashes on an event the app cannot avoid (a sheet opening). Continuing to tune
flags would be guessing.

Recovery is the correct layer for an intermittent host failure. It also makes
the suite honest: a flow is only retried when the *device* died, so a real
selector failure is still reported instead of being hidden by a retry.

## Verification

- `ensure-emulator.sh` on a live device returns the serial in 0.02 s.
- With no device it starts the AVD and returns once `sys.boot_completed=1`.
- A forced `pkill` mid-suite was detected and recovered from: the runner
  restarted the AVD and continued.
- Two device-side failure shapes have to be recognised, and the first version of
  the trigger only caught one. When the emulator process survives but adbd stops
  serving, Maestro fails inside its own DADB transport
  (`RawAdbSocket.connect` -> `IOException: closed`, gRPC `UNAVAILABLE`) rather
  than reporting "0 devices connected" — and `adb get-state` still answers, so
  the liveness probe called the device healthy and the run reported ten false
  failures. The trigger now matches Maestro's transport errors, and recovery
  resets the host adb server first, relaunching only if the emulator itself is
  gone.
- A selector-must-not-match check guards the trigger: ordinary assertion
  failures ("Assertion is false: id: … is visible", "Element not found: …") must
  NOT be classified as device failures, or a real regression gets retried and
  hidden.
- Final state: `TAGS=smoke` through the rewritten runner is 13/13 green, and a
  killed device costs at most one flow.

## Consequences

- `just tm` is no longer all-or-nothing across a device death; at most one flow
  is reported lost per crash, and the rest still run.
- `SKIP_INSTALL=1` is honoured on recovery too, so a fast re-run stays fast; a
  full run without it reinstalls after every relaunch, which costs a Gradle
  build per crash.
- `ensure-emulator.sh` is now the documented way to get a device; the
  `singularity-todo-emulator-launch` skill points at it.
- The `show_ime_with_hard_keyboard 0` mitigation is kept — it reduces crash rate
  even though it does not eliminate it — but it is no longer described as the
  fix.
- The real fix is upstream: a corrected `TextureResize` in a future Android
  Emulator release.

## Links

- `scripts/ensure-emulator.sh` — device recovery
- `scripts/run-maestro.sh` — per-flow execution and retry
- `.agents/skills/singularity-todo-emulator-launch/SKILL.md`
- Supersedes the mitigation in `2026-09-28-emulator-gfxstream-colorbuffer-segv.md`
- Related: `2026-09-29-emulator-launch-recipe.md` (the launch flags)
