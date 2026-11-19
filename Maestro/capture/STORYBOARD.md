# The store screenshot storyboard (Android phone)

The story the Google Play listing tells, and the data it is told with.

Six raw captures, in this order. Not a tour of every screen: six images that
say what the app is for, in the order a stranger needs them.

The machine-readable half of this document is
[`manifest.yaml`](manifest.yaml) — the file names, the order and the screen
behind each capture. This document is the half that says *why* each image is
there. Change one, change the other.

The capture flows themselves live in `Maestro/flows/capture/` — one flow per
screenshot, in the order above, each of them seeding the dataset below before
it takes its shot. The local capture command runs them: `just sc` builds the
capture APK, installs it on the pinned device, runs the six flows in the order
below and checks every raw PNG against the profile in
[`manifest.yaml`](manifest.yaml).

## The story

| # | File | Screen | What is on it | The value it communicates |
|---|------|--------|---------------|--------------------------|
| 1 | `01-today.png` | Today (agenda) | Three tasks due today, one of them high priority, two of them inside a project | *Your day, in one list.* The app opens on what is due now — not on a dashboard, not on an inbox to sort. |
| 2 | `02-task-detail.png` | Task detail | "Review the launch checklist": the description, its date and its priority | *A task carries its context.* The detail screen answers "what, when and how urgent" without leaving the task. |
| 3 | `03-project-detail.png` | Project detail | "Home renovation": its own task list and the chip that says it has a sub-project | *Work is organised, not filed.* Projects hold tasks and nest — one screen shows both. |
| 4 | `04-upcoming.png` | Upcoming (timeline) | The coming days, one header per day, grouped by date — three headers fit the frame, the dataset runs to five | *Look ahead without a calendar.* What is coming, in order, without planning it. |
| 5 | `05-calendar.png` | Calendar (month) | The current month, with the tasks due in it — at minimum the three due today | *The month at a glance.* The same data as the timeline, on the calendar people already think in. |
| 6 | `06-notes.png` | Note preview | "Launch post outline": a paragraph of text and a link to the task it belongs to | *Notes that point at the work.* A note is not a side journal — it links back into the task. |

Order matters: the first image answers "what is this app", the second and third
answer "what can a task be", and the last three are the reasons to keep it —
planning ahead, a calendar, and notes that are attached to something.

## The demo data

The dataset lives in [`Maestro/capture/demo-dataset.yaml`](demo-dataset.yaml)
and is seeded through the existing `todo-debug://seed` contract. It is a
*definition*, not an append: the dataset resets its own profile's content first,
so a capture after a dozen failed runs produces the same profile as the first
one. Why it is shaped that way — relative dates, unique names, fixed project
ids — is in
[the dataset ADR](../../docs/decisions/2026-11-17-the-demo-dataset-is-a-seeded-definition.md).

What it holds:

| Kind | Name | Due | Priority | Project | Why it is in the set |
|------|------|-----|----------|---------|----------------------|
| Project | Home renovation | — | — | — | A root with a child project |
| Project | Kitchen | — | — | Home renovation | The child: on Android a sub-project is reached through its parent |
| Project | Website launch | — | — | — | The second root, so the tree is not a line |
| Task | Review the launch checklist | today | high | Website launch | The task the detail screenshot opens; also a row on Today |
| Task | Order the kitchen tiles | today | medium | Kitchen | A second Today row, in a project, with a description |
| Task | Book the electrician | today | — | — | A Today row with no project — ordinary, and it makes the set look lived in |
| Task | Write the launch post | +1d | medium | Website launch | The first Upcoming row, and the target of the note's link |
| Task | Measure the kitchen counter | +2d | — | Kitchen | An Upcoming row in the child project |
| Task | Send the deposit invoice | +3d | high | Home renovation | An Upcoming row in the root project |
| Task | Renew the domain name | +4d | — | Website launch | An Upcoming row far enough out to show the next header |
| Task | Practise the vocabulary list | +5d | — | — | The last Upcoming row, and the second task with no project |
| Note | Launch post outline | — | — | — | Links to "Write the launch post"; the Notes screenshot |
| Note | Kitchen measurements | — | — | — | A note that is only text |
| Note | Questions for the electrician | — | — | — | A note that is only text |

The table above was seeded on an emulator straight from `demo-dataset.yaml`
and read back out of the app's database: 3 projects, 8 tasks, 3 notes, the note
link resolving to the task it names. Seeding the whole set a second time left
the same profile behind — the same 8 tasks, 3 projects and 3 notes, no
duplicates — so the rows above are what a capture gets. A row of the *default*
profile survived the reset, which is the isolation the reset promises: the
capture set wipes its own profile and nothing else.

The dates are **relative** (`today`, `+1d` … `+5d`), never fixed, and the names
carry nothing personal: no people, no mailboxes, no phone numbers, no machine
names, no paths. A capture is published under the store's name, and it must not
carry the name of whoever took it.

## The calendar capture

The calendar screenshot shows **the month the capture happens in** — the month
the app opens on — and it promises the tasks of that month.

The dataset runs from today to +5d, so it crosses a month boundary whenever the
capture happens on the 29th, 30th or 31st. Nothing is fixed and nothing is
re-dated to avoid it: the tasks that fall into the next month belong to the
next month's grid, and the month view shows the tasks of the month it is on.
The capture is not wrong on those days — it shows a month that holds part of
the set.

What the calendar capture must show, on every day of the year, is the **three
tasks due today**: they are always in the current month, so they are the part
of the dataset the month view owes the reader. The flow that captures it
(#3004) asserts those three and not the whole set — a flow that asserted the
whole set would fail on the 29th of a month and pass on the 28th, which is a
flaky screenshot rather than a check.

## The capture profile

The profile is part of the contract — the same six images have to come out of
every capture machine, or the set cannot be compared with the previous one.

| | |
|---|---|
| Device | The AVD `capture_9x16` — a Pixel-class phone AVD whose own screen is 1080 × 1920 px (the SDK's `pixel_2` hardware profile) |
| Resolution | 1080 × 1920 px, density 420 dpi — the AVD's own screen, never an override |
| Aspect | 9:16 portrait. Google Play's phone-screenshot rule: every side 320–3840 px, and the longest side no more than twice the shortest. 9:16 portrait from 1080 × 1920 up is also the tier Google recommends for apps |
| Profile | The app profile `Play demo` — the dataset's own profile, so a capture reset cannot reach the data of the profile someone else is using |
| Locale | `ru-RU` for the app — `adb shell cmd locale set-app-locales com.singularity.todo --locales ru-RU` |
| Theme | Light — the app's own light scheme, not a custom colour |
| Font scale | 1.0 (the system default; a scaled font changes every layout) |
| Format | PNG, full resolution, no scaling — the raw capture is the source of truth |
| Status bar | Visible, clock only: no notifications, no carrier name, no battery percentage. Play asks that a screenshot show no service provider and no notifications |
| Permissions | Notifications granted before the capture; no permission dialog may be on screen |
| Session | The local (anonymous) profile, no account attached — so no email or user name is on any screen |
| First run | The spotlight tour dismissed if it is on screen, and the app left on the Today tab before the first capture |

The status bar and the navigation bar stay as they are: they are part of what
the app looks like in a hand.

The local capture command applies the device half of this profile — the app
locale, the light theme, the font scale, the status bar and the screen
timeout — before the first flow runs.

The profile is a contract, not a wish. The locale, the light theme and the
font scale have to hold, and each one is read back from the device after it
is set: a screenshot of the app in the wrong theme or the wrong language is
not the screenshot this storyboard documents, and no check on the picture's
shape would notice. A property the device refuses aborts the capture. Two
properties are best-effort and only cost a warning: the screen timeout, which
changes no pixel, and hiding the battery percentage, which the status bar may
refuse to give up.

The status bar is the same kind of contract. The command hides the battery
percentage and collapses the shade, and a device with a notification waiting
is refused: a notification on the screen is a notification in the picture, and
a store screenshot with somebody's message in it is not a screenshot anybody
may publish. The system's own notifications are left alone — they carry
nothing of the person who owns the device. A device that cannot be asked
about its notifications, whose shade cannot be collapsed, or whose
notification cannot be read refuses the capture as well: a status bar nobody
managed to read is not a clean one.

The notifications are looked at again before every single screenshot, and the
shade is collapsed again with them: a notification that arrives while a flow
plays is a notification in the picture, and a check that ran before the first
capture says nothing about the sixth one.

The theme and the font scale are the *app's* settings, not the system's: they
live in the app's own data, and a device where somebody once chose the dark
theme keeps the dark theme after an install. The command therefore stops the
app and resets its settings file before the first flow, so the app comes up
with its defaults — the system theme, which the command puts into light mode,
and a font scale of 1.0. A build that does not allow that reset, or a device
that cannot stop the app, aborts the capture: the pictures would show the app
in somebody's old look.

**The profile is the dataset's own.** `demo-dataset.yaml` switches to `Play
demo` before it writes anything, so the rows land in that profile and the reset
reaches that profile alone. A capture run therefore cannot destroy the data of
the profile a person is using on the device — the reset is still not
concurrency-safe *inside* the demo profile, so a capture run owns its device.

Two things about the profile that are not obvious. Both were measured on a
device rather than assumed; the reasons behind the device itself are in
[the device ADR](../../docs/decisions/2026-11-17-the-capture-device-is-a-9-16-avd.md).

**The AVD's own screen has to be 9:16.** The repository's default `pixel_6`
AVD — the one `scripts/ci/provision-avd.sh` creates — is 1080 × 2400, and
Play would reject a screenshot of it: 2400 is more than twice 1080. The
capture device is provisioned with a 9:16 screen instead, and the capture
flows must not `wm size` it into one. An override changes the logical
resolution and leaves the captured image at its physical size, so the PNG
would still be 1080 × 2400 and still be rejected — and the Maestro flows
address the screen in percentages, which an override moves out from under
them.

**The capture is in Russian, and the app is half English.** With the app
locale set to `ru-RU` the resource strings read Russian, but the bottom
navigation, the day-group headers ("Tomorrow", "Thu, 19 Nov"), the calendar's
weekday names ("Mon" … "Sun") and the month title are English — they are
built from the enum names or hardcoded, not from the resources. The date
labels *on the tasks* are always Russian (`RussianDateFormatter`). A capture
therefore shows Russian text next to English headings. That is the app's real
look, not a fault of the capture — the same caveat is written down in
[`docs/how-to/rustore-release.md`](../../docs/how-to/rustore-release.md).

## The capture device

The capture device is the AVD `capture_9x16`, created from the SDK's `pixel_2`
hardware profile — 1080 × 1920 px at 420 dpi, 9:16 portrait:

```bash
AVD_NAME=capture_9x16 \
SYSTEM_IMAGE="system-images;android-36;google_apis_playstore;x86_64" \
DEVICE=pixel_2 \
GPU_MODE=host \
bash scripts/ci/provision-avd.sh

AVD=capture_9x16 SERIAL=emulator-5554 ensure-emulator
```

`GPU_MODE=host` is the capture device's own ask, and it is not the repository's
GPU policy: the Maestro and instrumentation surfaces launch the emulator with
their own `-gpu` flags, and `provision-avd.sh` leaves an AVD's GPU settings
alone unless it is asked to change them. The `pixel_2` profile is written with
the GPU off, and software rendering is a startup crash on this host, so the
capture device asks for the host GPU explicitly.

Every screen of this storyboard was measured on this device: the six screens
were opened one by one, and no screen clips its content, hides a label under
the navigation bar or truncates a text. The two screens that changed what
the storyboard says are the ones that say more than the frame holds — the task
detail shows its project one scroll down, and the Upcoming timeline shows
three of the five seeded days at a time.

## What may not be in a capture

- Debug UI, the seed screen, a toast, a snackbar, a crash dialog.
- A keyboard or a focused text field with a caret.
- An empty screen. Every capture shows content; the dataset exists for that.
- A date that contradicts itself — a task due "today" on a screen that says
  another day. The relative dates of the dataset are what keep this true.
- Anything from a developer's own device: a personal account, a personal
  notification, a bookmark, a search history.
