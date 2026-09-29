---
name: singularity-todo-emulator-launch
description: Launch the Android emulator on this dev host so it stays up. Covers the exact verified command, why each flag is there, proving the process is alive (pgrep lies), waiting for boot, device prep for Maestro, lock cleanup after a crash, and crash triage by signature. Use whenever an emulator is needed — for Maestro flows, adb verification, or screenshots — and it is not running, or one died mid-session.
---

# Emulator Launch (this host)

One command works on this machine. Every deviation tried so far either fails to
start or kills the emulator mid-session — the flags are not tunables, they are
the fix for two documented host-specific crashes.

## The recipe

```bash
# 1. Clear any zombie + stale locks (a crashed run holds the AVD)
pkill -f "qemu-system-x86_64"; sleep 2
rm -f ~/.android/avd/Medium_Phone.avd/*.lock

# 2. Launch. Windowed, hardware GPU, camera and audio OFF, no snapshot.
emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim \
  -camera-back none -camera-front none -no-audio \
  > /tmp/emu.log 2>&1 &
sleep 8   # keep this shell call alive; see "Process survival" below
```

Completion criterion for step 2: the log exists and grew — `ls -la /tmp/emu.log`
shows bytes. `Created extended window` in the log means the GUI is up.

```bash
# 3. Wait for boot (observed: ~15 s to BOOT_COMPLETED, ~25 s to usable)
until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done
adb devices   # expect: emulator-5554   device
```

```bash
# 4. Prep for automation (same steps scripts/run-maestro.sh performs)
adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
adb -s emulator-5554 shell wm dismiss-keyguard
adb -s emulator-5554 shell settings put secure show_ime_with_hard_keyboard 0
adb -s emulator-5554 shell input keyevent 82
```

The `show_ime_with_hard_keyboard 0` line is a workaround for an emulator
gfxstream `TextureResize` segfault when the IME pops during text entry — see
`docs/decisions/2026-09-28-emulator-gfxstream-colorbuffer-segv.md`.

## Why each flag is load-bearing

| Flag | Without it |
|---|---|
| *(no `-gpu` flag — default hardware GPU)* | `-gpu swiftshader_indirect` and `-gpu off` both SIGSEGV on this host. Do not "fix" GPU problems with software rendering. |
| *(windowed — no `-no-window`)* | Headless SIGSEGVs before creating a window. The window is expected to appear on the desktop; if the user says "I don't see the emulator", it was launched headless. |
| `-camera-back none -camera-front none` | The virtual camera's webrtc thread aborts after ~9 min of uptime (`SIGABRT` in `libandroid-webrtc.so`, `event_engine` in the stack) and takes the whole emulator down mid-session. See `docs/decisions/2026-09-29-emulator-launch-recipe.md`. |
| `-no-snapshot-load` | Snapshots saved under one renderer fail to load under another (renderer-change trap in the Mesa ADR). |
| `-no-audio` | Removes a host-audio failure mode; harmless otherwise. |

## Proving it is alive — pgrep lies

`pgrep -f "emulator"` matches the very shell command doing the grepping, so it
reports QEMU RUNNING when nothing is there. Verify by reading `/proc` exe links:

```bash
for p in /proc/[0-9]*; do exe=$(readlink "$p/exe" 2>/dev/null); case "$exe" in
  *qemu-system*) echo "alive pid=${p#/proc/}";; esac; done
```

Related trap: a background-task harness may report the emulator task as
"failed" while qemu itself survived — the wrapper exits, the emulator does not.
The /proc scan above is the source of truth; the notification is not.

## Process survival

The tool harness tears down its shell after each call, and a plain `&` child
dies with it — *unless the launching call stays alive a few seconds* (hence
`sleep 8` in the recipe; verified end-to-end). If a launch must survive a shell
that exits immediately, use `setsid emulator ... < /dev/null & disown` — but
always confirm with the /proc scan, because a setsid launch that fails writes
nothing to its log and exits silently.

The `android-emulator` MCP plugin's `android_start_emulator` times out at 30 s
on this host without producing a device. Use the direct command above instead;
MCP `android_screenshot` / `inspect_screen` etc. work fine against the
already-running emulator.

## Crash triage by signature

| Signature | Meaning | Fix |
|---|---|---|
| Startup: `amdgpu: The CS has been rejected (-22)` + `IOT instruction (core dumped)` | Mesa + kernel regression on AMD Renoir | Update the system; no flag helps. `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md` |
| Startup: SIGSEGV under `-gpu swiftshader_indirect`, `-gpu off`, or `-no-window` | Known dead ends on this host | Drop the flag; run the recipe as written |
| Dies ~9 min in: `SIGABRT`, crash thread in `libandroid-webrtc.so` | Virtual camera webrtc thread | Relaunch with `-camera-back none -camera-front none` |
| `Running multiple emulators with the same AVD is an experimental feature` | A zombie qemu still holds the AVD | `pkill -f qemu-system-x86_64`, then `rm -f ~/.android/avd/Medium_Phone.avd/*.lock` |
| adb shows the device `offline` for a while after boot | Normal — registration lags boot | Wait; it flips to `device` within ~1 min |
| Crash dialog on the device screen ("keeps stopping") | The *app* crashed, not the emulator | Read `adb logcat -d -b crash` — that is an app bug, not an emulator problem |

## After a mid-session death

1. Relaunch with the recipe (step 1 clears locks).
2. Reinstall the app if it was a wipe: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
3. Rerun `adb logcat -c` before the next test so crash triage stays clean.

## Related

- `docs/decisions/2026-09-29-emulator-launch-recipe.md` — the flags decision + webrtc crash evidence
- `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md` — the GPU dead-ends table
- `singularity-todo-maestro-flows` — running flows once the emulator is up
- `singularity-todo-adb-workflow` — physical-device counterpart
