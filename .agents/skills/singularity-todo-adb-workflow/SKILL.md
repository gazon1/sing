---
name: singularity-todo-adb-workflow
description: Full adb workflow for UI verification on a physical Android device connected via USB. Covers pre-flight device checks, Gradle build + install, wake/unlock (delegates to singularity-todo-adb-screen-unlock), app launch via am start, screenshot via screencap, UI dump via uiautomator, logcat inspection, and tap/text input. Use when "run on device", "verify UI on phone", "adb screenshot" is requested instead of emulator. Replaces the android-emulator MCP workflow for physical hardware.
---

# ADB Workflow — UI Verification on Physical Android Device

Use this skill when the verification target is a physical phone/tablet connected via USB, not an AVD. For just the unlock step, delegate to `singularity-todo-adb-screen-unlock`.

## Prerequisites

1. `adb` available on PATH (`adb version` should print a version string).
2. Physical device connected via USB with USB debugging enabled.
3. `com.singularity.todo` installed (or being installed) on the device.

## Step-by-step workflow

### Step 1 — Pre-flight: confirm device

```bash
# List devices and extract the first available serial
adb devices -l
# Expected: "12345678  device product: Pixel7 model: Pixel_7 ..."
SERIAL=$(adb devices -l | grep ' device$' | head -1 | awk '{print $1}')
echo "Using device: $SERIAL"

# Verify device state
adb -s $SERIAL shell getprop ro.product.model
adb -s $SERIAL shell getprop ro.build.version.sdk
# If unauthorized — instruct human to tap "Allow USB debugging" on device
```

### Step 2 — Build + install (if APK changed)

```bash
# Fast incremental install (debug APK)
./gradlew :androidApp:installDebug

# Or with specific serial (if multiple devices)
SERIAL=12345678
./gradlew :androidApp:installDebug -Pandroid.device=$SERIAL
```

### Step 3 — Wake + unlock

Delegate to `singularity-todo-adb-screen-unlock`. TL;DR:

```bash
adb -s $SERIAL shell input keyevent KEYCODE_WAKEUP
adb -s $SERIAL shell input keyevent 82   # MENU — unlocks most non-PIN screens
# Fallback: adb -s $SERIAL shell input swipe 500 1500 500 500
```

### Step 4 — Launch the app

```bash
# Launch MainActivity (the app's main launcher)
adb -s $SERIAL shell am start -n com.singularity.todo/.MainActivity

# For a specific destination (if you know the deep-link intent):
adb -s $SERIAL shell am start -n com.singularity.todo/.MainActivity \
  -d "singularity://task/t1"
```

Wait for the app to settle (2–3 seconds) before taking screenshots.

### Step 5 — Screenshot

```bash
# Capture to device SD card, pull to local
adb -s $SERIAL shell screencap -p /sdcard/screen.png
adb -s $SERIAL pull /sdcard/screen.png /tmp/task-detail-screen.png
# View: open /tmp/task-detail-screen.png in your image viewer
```

### Step 6 — UI dump (XML hierarchy)

```bash
# Dump accessible hierarchy (for locating elements by contentDescription/text)
adb -s $SERIAL shell uiautomator dump /sdcard/ui.xml
adb -s $SERIAL pull /sdcard/ui.xml /tmp/ui.xml
# Inspect: cat /tmp/ui.xml | grep -E "text=|content-desc=" | head -40
```

### Step 7 — Tap / Swipe / Type

```bash
# Tap by coordinates (from UI dump or screenshot analysis)
adb -s $SERIAL shell input tap 540 960

# Type text (spaces = %s in older adb, actual spaces in newer)
adb -s $SERIAL shell input text "Buy%sgroceries"

# Swipe (useful for scrolling or dismissing overlays)
adb -s $SERIAL shell input swipe 500 1500 500 500

# Press back button
adb -s $SERIAL shell input keyevent KEYCODE_BACK
```

### Step 8 — Logs (crash / FATAL check)

```bash
# Last 200 lines of logcat, filter for app crashes and FATAL exceptions
adb -s $SERIAL logcat -d -t 200 | grep -iE "singularity|AndroidRuntime|FATAL|backtrace"

# Full logcat (for deep debugging)
adb -s $SERIAL logcat -d > /tmp/full-logcat.txt

# Clear logcat buffer before a test run (for clean state)
adb -s $SERIAL logcat -c
```

## Full one-page script for copy-paste

```bash
SERIAL=$(adb devices -l | grep ' device$' | head -1 | awk '{print $1}')
echo "=== Device: $SERIAL ==="
adb -s $SERIAL shell getprop ro.product.model

echo "=== Wake + Unlock ==="
adb -s $SERIAL shell input keyevent KEYCODE_WAKEUP
adb -s $SERIAL shell input keyevent 82

echo "=== Install ==="
./gradlew :androidApp:installDebug

echo "=== Launch ==="
adb -s $SERIAL shell am start -n com.singularity.todo/.MainActivity
sleep 3

echo "=== Screenshot ==="
adb -s $SERIAL shell screencap -p /sdcard/screen.png
adb -s $SERIAL pull /sdcard/screen.png /tmp/screen.png
echo "Screenshot at /tmp/screen.png"

echo "=== Logs ==="
adb -s $SERIAL logcat -d -t 50 | grep -iE "singularity|AndroidRuntime|FATAL"
```

## Relationship to android-emulator MCP

This skill is the physical-device counterpart to the `android-emulator:android-dev` skill. The MCP tools (`android_build_and_run`, `android_screenshot`, `android_ui_describe`, `android_ui_tap`) also work with a physical device when you pass `serial=$SERIAL`. Use whichever interface is more convenient — the underlying protocol is the same `adb` commands shown above.

## When to use this vs android-emulator skill

| Situation | Use |
|---|---|
| Physical phone/tablet connected | This skill (adb direct) |
| AVD / emulator | `android-emulator:android-dev` MCP |
| Quick tap/screenshot on known serial | MCP tools with `serial=` |
| Full verification run (build + install + wake + screenshot + logs) | This skill |
| Starting a fresh AVD | `android-emulator:android-dev` |
