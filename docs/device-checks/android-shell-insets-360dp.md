# Device check — the Android shell's status bar and content padding on a 360 dp phone

Covers #2165, #2166, #2168, and the runtime half of #1699 / #2192.

## Status: **not run.** This change could not compile, install, or launch the app.

The build and the device run are refused in the environment this change was produced in
(the run is not permitted to compile). So there are **no screenshots and no on-device
observation** attached to this change, and the acceptance criteria of #2165 / #2166 / #2168
that ask for one paragraph per screen are **not met**. This file records exactly what is
and is not established, so the next run starts from a fact rather than from a KDoc.

## What is established

- The insets the shell's scaffold pads its content by are computed by
  `shellContentWindowInsets(windowInsets, navigationPadding)` in
  `shared/src/androidMain/kotlin/com/singularity/todo/shell/ShellInsets.kt`, and the call
  site is `AndroidShellNav3.kt` — the `NavigationSuiteScaffold` content lambda takes the
  suite's `PaddingValues` and folds it into the inner `Scaffold`'s `contentWindowInsets`.
- That helper is covered by `ShellInsetsTest` (androidHostTest, Robolectric), including the
  shape the shell actually hands in (full `ScaffoldDefaults.contentWindowInsets`, so the
  bottom edge is the system navigation bar's inset, not a structural zero), the rail case,
  and the top edge being dropped so `ShellTopBar` keeps owning the status bar (#1566).
- The call site is covered by `NoDroppedNavigationPaddingRule` and its tests: the pre-fix
  shape fails, and the two ways of spending the padding pass.

## What is **not** established, and why it matters

`NavigationSuiteScaffold` hands its content a `PaddingValues` whose bottom edge is the
height of the navigation suite's bar. Whether that height **contains** the system
navigation bar's inset or **sits on top of it** decides the arithmetic:

| library behaviour | correct content bottom | `union` (what the code does) |
| --- | --- | --- |
| bar height contains the system inset | `max(d36, suiteBottom)` | correct |
| bar height excludes it | `suiteBottom + systemBottom` | **short by `systemBottom`** — the last row goes under the system bar |

Nothing in the source can answer this; it is a property of the pinned library version
(`material3-adaptive-navigation-suite` 1.13.0-beta01, resolving to
`androidx.compose.material3:material3-adaptive-navigation-suite-android` 1.5.0-beta01 at
build time). The previous version of this change asserted the first row of that table in a
KDoc and tested it with an input the call site could not produce; that is the finding this
file exists to stop repeating.

## What the code does about it now

The shell measures both numbers on every composition and logs them under the tag
`SHELL-INSETS` (`AndroidShellNav3.kt`, in the `NavigationSuiteScaffold` content lambda):

```
adb logcat -s SHELL-INSETS
I/SHELL-INSETS: systemBottomPx=48 suiteBottomPx=160 contentBottomPx=160 density=2.0
```

- `systemBottomPx` — the window's `WindowInsets.systemBars` bottom edge.
- `suiteBottomPx` — the suite's `PaddingValues` bottom edge (`calculateBottomPadding()`).
- `contentBottomPx` — what `shellContentWindowInsets` produced, i.e. what the content is
  padded by.

If `suiteBottomPx < systemBottomPx` the assumption is violated and the shell logs an
**error** naming the arithmetic to change. A library upgrade that changes the answer now
announces itself instead of silently eating the last row.

So the first `adb logcat -s SHELL-INSETS` on the device answers the question in one line:

- `suiteBottomPx = 160` (80 dp) and `systemBottomPx = 48` (24 dp) → the bar contains the
  system inset, `union` is right, `contentBottomPx` must be `160`;
- `suiteBottomPx = 160` with an error line → the bar does not contain it; change
  `shellContentWindowInsets` to add `systemBottom` to the suite's bottom instead of taking
  the union, and update `the_measured_phone_case_adds_no_gap_under_the_navigation_bar`.

## How to finish this check

On the 360 dp × 800 dp Android 15 phone (`0O65321F32204DD5` reports `720x1600` at density
`320`):

```bash
./gw :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.singularity.todo/.MainActivity
adb logcat -c && adb logcat -s SHELL-INSETS    # the one line that decides the arithmetic
```

Then, with the app running, capture and describe:

1. **Calendar, scrolled to the end of the month** — the "2–8" week row must be fully
   visible above the navigation bar, not cut in half.
2. **Settings** — the "Font Size" card fully visible; a task card's last row ("Not pinned")
   not flush against the bar and not under it.
3. **The app bar area** — the bar below the status bar, no screen content under the status
   bar, and no doubled top padding (report it if present: that is #1566, out of scope to
   fix here).
4. **The "Add task" FAB** — above the bottom navigation bar, not drawn behind it.

```bash
adb exec-out screencap -p > docs/device-checks/calendar-end-of-month.png
```

Attach those four screenshots to the pull request with one paragraph per screen saying what
was seen, including anything still wrong — that is #2165's last acceptance criterion, and it
is the one this file cannot satisfy.
