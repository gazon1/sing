---
name: singularity-todo-adb-screen-unlock
description: How to wake and unlock a physical Android device connected via adb before UI verification. Use when "разблокируй экран", "wake device", "unlock phone" is requested, or before any adb screenshot/tap workflow on a physical device. Covers KEYCODE_WAKEUP, KEYCODE_MENU (unlocks most non-PIN lockscreens), swipe fallback for gesture/PIN/pattern locks.
---

# ADB Screen Unlock — Wake a Physical Android Device

Physical devices lose their lockscreen between test sessions. Every UI verification run on a real device must wake + unlock before proceeding.

## The unlock sequence

```bash
# 1. Wake the screen (backlight on, lockscreen visible)
adb -s $SERIAL shell input keyevent KEYCODE_WAKEUP

# 2. Dismiss lockscreen — MENU key unlocks without PIN on most devices
# (works for "swipe to unlock", "face unlock", no-lock setups)
adb -s $SERIAL shell input keyevent 82   # 82 = KEYCODE_MENU

# 3. If still locked (PIN / pattern / password) — fallback: swipe up
adb -s $SERIAL shell input swipe 500 1500 500 500
```

**Why `KEYCODE_MENU` (82)?** Most Android lockscreens treat MENU as "dismiss overlay" rather than opening the app drawer. It is the fastest unlock path that requires no knowledge of the user's PIN/pattern.

**When MENU fails:** The device has a secure lockscreen (PIN/pattern/password). Use the swipe fallback first — it dismisses "swipe to unlock" overlays and gesture-nav "pill" prompts. For PIN/pattern, the swipe may not fully unlock; note this in the verification output.

## What `$SERIAL` is

`$SERIAL` comes from `adb devices -l`. Always verify the device is present before unlocking:

```bash
adb devices -l
# Output: List of devices attached
#         12345678  device product: Pixel7 model: Pixel_7 device: panther
#         $SERIAL = 12345678
```

If `state=unauthorized`: the device shows "Allow USB debugging?" on its screen — the human must tap "Allow" and "Always allow from this computer".

## Full pre-flight checklist before every session

```bash
# 1. Confirm device is available and in device state
adb devices -l | grep -E "device$" || { echo "No device in 'device' state"; exit 1; }

# 2. Read device properties
adb -s $SERIAL shell getprop ro.product.model
adb -s $SERIAL shell getprop ro.build.version.release
adb -s $SERIAL shell getprop ro.build.version.sdk

# 3. Confirm screen is on (optional — power state)
adb -s $SERIAL shell dumpsys power | grep -E "mWakefulness|mScreenOn"
```

## One-shot command for convenience

Combine into one shell snippet for copy-paste:

```bash
SERIAL=$(adb devices -l | grep ' device$' | head -1 | awk '{print $1}')
adb -s $SERIAL shell input keyevent KEYCODE_WAKEUP
adb -s $SERIAL shell input keyevent 82
```

## Relationship to android-emulator MCP

This skill replaces `mcp__android_emulator__android_start_emulator` when the target is a physical device (no AVD involved). The MCP tool `android_start_emulator` launches a virtual device; this skill operates on hardware you already have connected.

> **If `android_start_emulator` produces a process that dies on startup** with
> `amdgpu: The CS has been rejected (-22)` + `IOT instruction (core dumped)`,
> that is a Mesa + kernel regression on AMD Renoir — fix it by updating the
> system, not by changing emulator flags. Then wait for the one-time
> "Allow USB debugging" tap before running this skill. If the AVD aborts with
> `eglMakeCurrent failed`, drop the stale snapshot
> (`rm -rf ~/.android/avd/<AVD>/snapshots/*`) and cold-boot. See
> `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md`.

The upstream `singularity-todo-adb-workflow` skill uses this as its first step (wake/unlock) before build/install/launch.

## Anti-patterns

- **`adb shell input keyevent KEYCODE_POWER`** — this toggles power, potentially turning the screen OFF if it was already on. Use `KEYCODE_WAKEUP` instead.
- Skipping unlock assuming the screen is already open — always run the sequence; it is idempotent (pressing MENU on an already-unlocked screen does nothing harmful).
- Hardcoding a serial number — always derive `$SERIAL` from `adb devices -l` at runtime, as multiple devices may be connected across sessions.
