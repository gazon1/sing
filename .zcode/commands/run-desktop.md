# /run-desktop — Launch desktop app

Runs the desktop Compose app via Gradle.

## Usage

```
/run-desktop
```

## What it does

```bash
./gradlew :desktopApp:run
```

The app launches in a visible OS window (singleWindowApplication).

## For faster iteration (hot reload)

After adding the composeHotReload plugin, use:

```bash
./gradlew :desktopApp:hotRun --auto
```

This enables Compose Hot Reload — code changes are reflected without restarting the process.

## Run without window (headless Linux)

For CI/sandbox environments on Linux:

```bash
xvfb-run -a ./gradlew :desktopApp:run
```

## Desktop UI test (no window)

To run Compose UI tests without any window:

```bash
./gradlew :desktopApp:jvmTest
```

This runs `AppSmokeTest` — verifies the app launches and shows all 5 bottom nav items.
No emulator, no window, runs in ~10 seconds.
