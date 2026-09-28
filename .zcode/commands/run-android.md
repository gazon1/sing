# /run-android — Build, install and launch Android app via MCP

Uses the android-emulator MCP tools to:
1. Preflight check (JDK, SDK, AVDs)
2. Discover project structure
3. Build and run on emulator or USB device
4. Take a baseline screenshot

## Usage

```
/run-android [serial]
```

- `serial` (optional): Android device serial. If omitted, uses a ready emulator or starts a new one.

## What it does (MCP sequence)

```
1. mcp__android_emulator__android_preflight
2. mcp__android_emulator__android_discover_project
3. mcp__android_emulator__android_build_and_run (serial=<serial>)
4. mcp__android_emulator__android_screenshot  ← baseline
5. mcp__android_emulator__android_ui_status
6. mcp__android_emulator__android_logs (check for crashes)
```

## Exit on failure

If preflight fails (no SDK, no AVD, no licenses), stop and report:
> "Android SDK not configured. Please install SDK and accept licenses: sdkmanager --licenses"

## Requirements

- `adb` in PATH
- Android SDK installed
- AVD created, or accept automatic creation
- App signed with debug keystore (default)

> **Если эмулятор падает на старте** с `amdgpu: The CS has been rejected (-22)`
> и `IOT instruction (core dumped)` — это регрессия связки Mesa + ядро на AMD
> Renoir, а не AVD и не режим GPU. Лечится обновлением системы
> (`sudo dnf upgrade --refresh kernel kernel-core mesa-dri-drivers
> mesa-vulkan-drivers && sudo reboot`), а не флагами: `-gpu`, `-accel` и
> `-no-window` не помогают. Проверено на Fedora 44 (Mesa 26.2.3, ядро 7.2.7) —
> работает штатным `emulator`, без обёрток. Подробности:
> `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md`.

## For UI automation after launch

See `/run-android-ui` for the full interaction loop (describe → resolve → tap → screenshot → logs).
