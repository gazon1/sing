# Environment — the one device both apps were measured on

Everything in this folder was taken on this machine, this phone, this session.

| | Value |
|---|---|
| Serial | `0O65321F32204DD5` |
| Model | `RMX3938` (Realme, `ro.product.model`) |
| Android | 15, SDK 35 (`ro.build.version.release` / `.sdk`) |
| Screen | `wm size` → **720×1600 px** |
| Density | `wm density` → **320** → **1dp = 2px** |
| Logical size | 360 × 800 dp |
| Host | `fedora`, adb over USB (`transport_id:1`) |

Consequence for reading these documents: **every dp here is a pixel count divided by two**, taken
either from `uiautomator dump` bounds (Singularity Todo) or from screenshot pixels (SingularityApp,
Flutter, no semantics). No value is copied from desktop CSS.

---

## Applications

| | Singularity Todo | SingularityApp |
|---|---|---|
| Package | `com.singularity.todo` | `com.sibirix.singularityapp` |
| Version name | `0.1.0+g29ccc0964fb7` | `10.23.0` |
| Version code | `1` | `10235100` |
| Installed | 2026-11-12 01:38 | (pre-installed) |
| Last updated | 2026-11-12 15:59 | — |
| UI toolkit | Compose Multiplatform | Flutter (`assets/flutter_assets`, `NOTICES.Z`) |
| Theme | light (`surface #F8F9FF`) | dark only (`#080D21` page, `#0A1329` sheet) |
| Accessibility tree | full (`uiautomator` sees text, bounds, `content-desc`) | **empty** — Flutter semantics off, `uiautomator dump` returns `text=""` |

The accessibility difference is why the two apps are measured by two different methods. It also
means **SingularityApp was never verified for screen-reader labels** in this study; nothing is
claimed about its accessibility.

---

## How each measurement was taken

### Singularity Todo

```bash
adb -s $S shell uiautomator dump /sdcard/ui.xml
adb -s $S pull /sdcard/ui.xml /tmp/ui.xml
```

Then every `bounds="[l,t][r,b]"` is parsed. Two cautions proven in this repo before
(`docs/research/2862-project-navigation-comparison.md`):

- **`IconButton` inflates a clickable node to its 48dp minimum touch target**, so a clickable bound
  can be taller than the row drawn on screen. Row pitches below are therefore cross-checked against
  the screenshot ink, and any bound that is *not* an icon button is used as-is.
- Text nodes are the reliable source for label position; container nodes are the reliable source for
  card edges.

### SingularityApp

No semantics, so:

```bash
adb -s $S shell screencap -p /sdcard/s.png
```

and the geometry is read from the image with PIL: scan a column for card/background transitions
(card `#0A1329`, page `#080D21`, gap = page colour), and measure text ink bands inside a known
column range. Font sizes are *inferred* from ink height and are marked as such wherever used.

### Colour and contrast

Sampled from the PNG at a named point, then WCAG relative luminance computed from those values
(sRGB, D65). Ratios in `comparison/visual-analysis.md` are reproducible from the screenshots in this
folder.

---

## Commands used (all read-only except where noted)

```bash
SERIAL=0O65321F32204DD5

# device identity
adb -s $SERIAL devices -l
adb -s $SERIAL shell getprop ro.product.model
adb -s $SERIAL shell wm size; adb -s $SERIAL shell wm density
adb -s $SERIAL shell pm list packages -3
adb -s $SERIAL shell dumpsys package com.singularity.todo | grep -E "versionName|versionCode"

# read the live database (debug build; run-as works)
adb -s $SERIAL exec-out run-as com.singularity.todo cat databases/todo.db     > todo.db
adb -s $SERIAL exec-out run-as com.singularity.todo cat databases/todo.db-wal > todo.db-wal
adb -s $SERIAL exec-out run-as com.singularity.todo cat databases/todo.db-shm > todo.db-shm

# drive the UI
adb -s $SERIAL shell input tap X Y
adb -s $SERIAL shell input swipe X1 Y1 X2 Y2 MS
adb -s $SERIAL shell input text "ascii-only"
adb -s $SERIAL shell input keyevent 4      # BACK
adb -s $SERIAL shell input keyevent 66     # ENTER

# launch
adb -s $SERIAL shell am start -n com.singularity.todo/.MainActivity
adb -s $SERIAL shell monkey -p com.sibirix.singularityapp -c android.intent.category.LAUNCHER 1
```

**Always pull the `-wal` next to the `.db`.** A `todo.db` copied without its WAL shows stale rows:
in this session the priority write, the new checklist item and the new task were all invisible until
the WAL was pulled too. That mistake is easy to make and it makes "the app did not save" look true.

**`adb shell input text` cannot type Cyrillic on this ROM** — it throws
`NullPointerException: Attempt to get length of null array` inside `InputShellCommand.sendText`.
Scenarios that needed a search used a Latin substring of a real title (`pdf`, `head`, `Backlog`,
`ux-prob`).

Screenshot helper used for every image: `.tools/shot.sh <out.png> [ui-dump.xml]`.

---

## Reproducing the session

1. `adb devices -l` → expect one physical device, `device` state, not `emulator-`.
2. `docs/research/task-detail-ux/.tools/shot.sh /tmp/check.png` → a screenshot of the phone.
3. Open Singularity Todo → Today. The imported profile should read
   `Overdue · 7040` and `Today · 4` (the numbers move as the day advances and as probes are added).
4. Open any task → the states in `singularity-todo/screen-inventory.md` reproduce directly.
5. Open SingularityApp → Today → tap a task → its detail opens as a bottom sheet.

---

## What is *not* measured here

- Font scale > 1.0 (the OS default was used; `createTypography(scale)` exists but was not exercised).
- Dark theme in Singularity Todo (the app followed the light system theme).
- Landscape, tablets, Desktop.
- Animation timings (screenshots cannot show them; only their presence/absence is noted).
- Performance (only one visible symptom: the cold-start gate "Checking version…" holds the app for
  several seconds before the list appears — `singularity-todo/screenshots/` does not include it, the
  observation is recorded in `singularity-todo/interactions.md`).
