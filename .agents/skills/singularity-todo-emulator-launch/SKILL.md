---
name: singularity-todo-emulator-launch
description: Launch and recover the Android emulator on this dev host. Covers the verified launch command, why each flag is there, proving the process is alive, crash triage by signature, and — most importantly — recovery when the host's gfxstream bug kills it mid-session. Use whenever an emulator is needed (Maestro flows, adb verification, screenshots) and one is not running or has died. For scripted runs prefer scripts/ensure-emulator.sh, which does all of this.
---

# Emulator Launch and Recovery (this host)

## Use the script, not this file, for anything scripted

`scripts/ensure-emulator.sh` prints a ready serial on stdout, or starts the AVD
with the verified flags, waits for boot, and prints the serial. It is the
recovery primitive for the whole repo:

```bash
SERIAL=$(./scripts/ensure-emulator.sh)     # start if needed, wait for boot
./scripts/run-maestro.sh                   # runs per-flow and relaunches on its own
```

Everything below is for the interactive case and for understanding *why* the
script does what it does.

## The launch command

```bash
# A crashed run leaves the AVD locked; a second qemu refuses to start.
pkill -9 -f "qemu-system-x86_64 -avd Medium_Phone"; sleep 2
rm -f ~/.android/avd/Medium_Phone.avd/*.lock

emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim \
  -camera-back none -camera-front none -no-audio > /tmp/emu.log 2>&1 &
sleep 10    # keep this shell call alive; see "Process survival"
```

Completion criterion: the log exists and grew, and contains
`Created extended window`. Boot takes ~12–60 s; `adb devices` should show
`emulator-5554   device`.

Why each flag is load-bearing:

| Flag | Without it |
|---|---|
| *(no `-gpu` flag — default host GPU)* | `-gpu software` and `-gpu swangle` both die during start-up on this API 36 AVD. **This is the only mode that boots**, despite being the one that crashes later. |
| *(windowed — no `-no-window`)* | Headless SIGSEGVs before the window is created. |
| `-camera-back none -camera-front none` | The virtual camera's WebRTC thread aborts the process after ~9 min (`SIGABRT` in `libandroid-webrtc.so`). |
| `-no-snapshot-load` | A snapshot saved under one renderer floods the log and aborts under another. |
| `-no-audio` | Removes an unrelated host-audio failure mode. |

## The gfxstream crash — expect it, recover from it

The host emulator segfaults in its render thread whenever the guest asks for a
new surface (a bottom sheet, a dialog, a navigation push):

```
#0  __strlen_avx2
#1  gfxstream::host::gl::TextureResize::TextureResize(unsigned, unsigned)
#2  gfxstream::host::gl::ColorBufferGl::create(...)
...
#9  gfxstream::host::RenderThread::main()
```

An earlier ADR blamed the soft keyboard and shipped
`settings put secure show_ime_with_hard_keyboard 0` as the fix. A control run
disproved that: with the IME disabled outright the crash still reproduced with
an identical stack. **The IME is a trigger, not the trigger** — any new surface
does it, and the app cannot avoid opening surfaces.

No configuration both boots and avoids it. So the answer is recovery, not
prevention: `scripts/run-maestro.sh` runs one flow per Maestro invocation and
relaunches the AVD when the device is lost, so one crash costs at most one flow
instead of the whole suite. Full reasoning:
`docs/decisions/2026-09-29-emulator-crash-recovery-runner.md`.

## Proving it is alive — pgrep lies

`pgrep -f "emulator"` matches the very shell command doing the grepping, so it
reports QEMU RUNNING when nothing is there. Read `/proc` exe links instead:

```bash
for p in /proc/[0-9]*; do exe=$(readlink "$p/exe" 2>/dev/null); case "$exe" in
  *qemu-system*) echo "alive pid=${p#/proc/}";; esac; done
```

Inside a **script file** `pgrep -f` is safe — the invoking process's command
line is just `bash script.sh` — which is why `ensure-emulator.sh` uses it.

A background-task harness may report the emulator task "failed" while qemu
itself survives: the wrapper exits, the emulator does not. The `/proc` scan is
the source of truth, not the notification.

## Liveness is not `adb get-state`

After the render thread dies, adb keeps reporting the serial as `device` for a
while before it flips to `offline`. A check based only on `get-state` sees a
healthy device that cannot serve a single command. Require a shell round trip:

```bash
adb -s "$SERIAL" get-state | grep -q '^device$' \
  && adb -s "$SERIAL" shell echo ping | grep -q ping
```

## Process survival

The tool harness tears down its shell after each call, and a plain `&` child
dies with it — *unless the launching call stays alive a few seconds*, hence
`sleep 10` above. `setsid ... & disown` also works but a setsid launch that
fails writes nothing to its log and exits silently, so it needs the `/proc`
check to confirm.

The `android-emulator` MCP plugin's `android_start_emulator` times out on this
host without producing a device. Its other tools (screenshot, UI inspect) work
fine against an already-running emulator.

## Crash triage by signature

| Signature | Meaning |
|---|---|
| `SIGSEGV`, render thread, `TextureResize` → `ColorBufferGl::create` | The known gfxstream bug. Relaunch; it is not the app's fault. |
| `SIGABRT` in `libandroid-webrtc.so`, uptime ~9 min | Virtual camera. Relaunch with `-camera-* none` (already the recipe). |
| Startup `amdgpu: The CS has been rejected (-22)` + `IOT instruction` | Mesa/kernel regression on AMD Renoir — **a different, already-fixed problem**; do not confuse it with the above. `2026-09-28-emulator-mesa-radeon-cs-rejected.md` |
| Startup SIGSEGV with `-gpu software` / `-gpu swangle` / `-no-window` | Known dead ends on this AVD. Use the recipe as written. |
| `Running multiple emulators with the same AVD` | A zombie qemu still holds the AVD. `pkill`, then remove `~/.android/avd/<AVD>.avd/*.lock`. |
| Device listed but every flow fails in ~10 ms | The device died and is gone. `adb devices` confirms; relaunch. |
| System "keeps stopping" dialog on the device | The **app** crashed. `adb logcat -d -b crash` names the real bug. |

## After a mid-session death

```bash
SERIAL=$(./scripts/ensure-emulator.sh)     # relaunches and waits for boot
adb -s "$SERIAL" shell settings put secure show_ime_with_hard_keyboard 0
adb -s "$SERIAL" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## Related

- `scripts/ensure-emulator.sh` — the recovery primitive (new)
- `docs/decisions/2026-09-29-emulator-crash-recovery-runner.md` — the control run
- `docs/decisions/2026-09-29-emulator-launch-recipe.md` — the launch flags
- `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md` — a different GPU fault
- `singularity-todo-maestro-flows` — running flows once the emulator is up
- `singularity-todo-adb-workflow` — physical-device counterpart
