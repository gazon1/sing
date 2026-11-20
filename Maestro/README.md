# Maestro UI flows

End-to-end UI tests for the Android app, driven by [Maestro](https://maestro.mobile.dev)
against a real device or emulator.

> **The seed contract can delete data.** A `todo-debug://seed` link with
> `reset=content` wipes every row of the active profile's content — tasks,
> notes, projects, tags, reminders, attachments, saved views — and
> `cleanup=<prefix>` deletes the rows whose title starts with that prefix. Both
> are documented in [`helpers/seed.yaml`](helpers/seed.yaml). Run them only
> against a device whose data you are willing to lose: the demo dataset resets
> a profile of its own, but a hand-written `reset=content` reaches whichever
> profile is active when it runs.

## Running

```bash
just tm                                   # every flow
just tm tags=smoke                        # only the smoke subset
just tm flow=Maestro/flows/tasks          # one directory
SERIAL=emulator-5554 just tm tags=smoke # pin the device (recovery still runs)

# As part of the device lane (opt-in; needs maestro CLI + a booted device):
RUN_MAESTRO=1 hk run device
```

Environment variables understood by `run-maestro`:
`SERIAL`, `FLOW`, `TAGS`, `SKIP_INSTALL=1`, `MAESTRO_MAX_RETRIES` (default 1).

### The second engine: maestro-runner

The same flow files can be run by [maestro-runner](https://github.com/devicelab-dev/maestro-runner),
a single Go binary that reads the same YAML without a JVM. The CLI starts a JVM
per flow, and a suite of 127 flows pays that warm-up 127 times.

```bash
scripts/run-maestro-e2e.py --engine maestro-runner -f Maestro/flows/smoke
MAESTRO_ENGINE=maestro-runner scripts/run-maestro-e2e.py -f Maestro/flows/smoke
```

The CLI stays the default: the failure floor of this repository was calibrated
with it, and a run that quietly used another executor would be a measurement of
something else. The engine is named in the run log, and the report is rewritten
into the shape the gates read (the CLI writes the flow's path into the `file`
attribute of the JUnit `testcase`, maestro-runner writes it as a `property`).

**Installing it.** The runner needs its driver binaries next to the binary: the
release tarball unpacks to `<root>/bin/maestro-runner` and `<root>/drivers`.
A copy of the binary alone cannot drive a device — it exits with
`drivers directory not found` — so the tarball is what gets unpacked:

```bash
VERSION=1.1.31
BASE="https://github.com/devicelab-dev/maestro-runner/releases/download/v${VERSION}"
curl -sSLO "${BASE}/maestro-runner-${VERSION}-linux-amd64.tar.gz"
curl -sSLO "${BASE}/maestro-runner-${VERSION}-linux-amd64.tar.gz.sha256"
sha256sum -c "maestro-runner-${VERSION}-linux-amd64.tar.gz.sha256"
tar -xzf "maestro-runner-${VERSION}-linux-amd64.tar.gz" -C ~/.local/maestro-runner/${VERSION}
export PATH="$HOME/.local/maestro-runner/${VERSION}/maestro-runner/bin:$PATH"
```

mise is not used for it: every mise backend that can pick the platform for us
installs the binary alone and drops the drivers. The version this repository
measured is `1.1.31`; the runner's own version is printed in the run log, and a
different build is called out as not comparable. `MAESTRO_RUNNER_HOME` overrides
where the distribution is looked for.

### Getting a device

```bash
SERIAL=$(ensure-emulator)   # prints a serial, starting the AVD if needed
```

That is the only supported way to get a device: it prints a ready serial, or starts
the AVD and waits for boot. A hand-rolled `adb devices | awk` can only ever find a
device that was *already* up, so every emulator crash would surface as "connect a
device" — when the remedy is one command.

**Pinning `SERIAL` does not switch recovery off.** A pinned-but-unready *emulator*
is exactly the crash case `ensure-emulator` exists to resurrect, so it restarts
the AVD instead of failing. Only a pinned *physical* device is a hard error, because
starting an AVD would land on `emulator-5554` and not match.

`run-maestro` calls this itself when no device is ready, and again whenever
the device is lost mid-suite. That is not defensive padding: the host emulator
has an unfixed gfxstream crash (`TextureResize` segfault while the guest
creates a ColorBuffer) that takes the whole device with it. No renderer
configuration avoids it — `-gpu software` and `-gpu swangle` both die at
start-up on this AVD — so the suite recovers rather than preventing. See
`docs/decisions/2026-09-29-emulator-crash-recovery-runner.md`.

Consequences for reading a run: flows run one per Maestro invocation, so a
device death costs at most one flow instead of the whole suite; and a flow is
retried only when Maestro itself reported no device, never for a genuine
assertion failure.

Measured (2026-09-29, A/B): ~1 device loss per 2-3 smoke passes under the
shipped runner; `-no-metrics`, `-crash-report-mode disabled` and
`ANDROID_EMULATOR_FEATURES` toggles do **not** reduce it, and plain adb churn
(taps, screenshots, hierarchy dumps, rotation — 160+ iterations) never kills
it. The trigger is specific to Maestro's driver interaction and is upstream;
the mitigation experiments and their results are recorded in
`docs/decisions/2026-09-29-emulator-crash-recovery-runner.md`.

## Store screenshots

The store listing needs six raw captures, and they are the one thing in this
repository that is *produced* rather than *measured*: the flows under
`Maestro/flows/capture/` drive the app into a known state, and the command
below photographs the result and checks it.

```bash
just sc                                          # the whole set
just sc 01-today                                 # one capture, for iteration
SERIAL=emulator-5554 just sc                     # pin the capture device
just android::store-capture 01-today emulator-5554   # both, positionally
```

`just` binds arguments by position, so the recipe takes the capture name, then
the serial, then an APK path. The serial is not optional: the capture device
is always named, because one attached device is just as likely to be somebody's
phone as the capture AVD.

The command:

- builds this checkout's debug APK — or takes the one `--apk` names — and
  installs it on the pinned device;
- refuses to go on when the device is not named or not attached, and when the
  build on the device is not the build in the APK: a capture from a stale
  install looks exactly like a good capture;
- runs the capture flows in the order `Maestro/capture/manifest.yaml` gives,
  one flow per invocation, and takes the screen right after each of them;
- resets the app's own settings and puts the device into the capture profile
  before the first flow — the theme and the font scale are the app's settings,
  and the system's settings do not reach them. The locale, the light theme and
  the font scale are read back from the device afterwards; one that did not
  take refuses the capture, because a picture of the app in the wrong theme is
  not the picture the storyboard documents. Two properties are best-effort and
  only cost a warning: the screen timeout, which changes no pixel, and hiding
  the battery percentage, which the status bar may refuse to give up;
- writes the raw PNGs into `build/store-capture/`, wiped at the start of every
  run — a stale image must not be able to stand in for a capture that did not
  happen;
- checks every image: it is a readable PNG, its chunks are whole, its header
  describes a shape the checker can measure, it carries the pixels that header
  promises, every scanline uses a filter PNG knows, and it is the size and
  shape the manifest documents;
- leaves the Maestro logs and the JUnit reports in `build/store-capture/`, and
  exits non-zero when a flow or an image failed.

The storyboard — what the six images are, and why — is
[`capture/STORYBOARD.md`](capture/STORYBOARD.md).

### From raw captures to the store set

The raw captures are photographs of the app. The store listing wants composed
pictures — a headline above the phone — and Google Play has rules about their
size, shape and format. The renderer does that second half:

```bash
just sr                                          # the whole set
just sr build/store-capture build/store-assets   # explicit directories
```

It reads the raw captures, the compositions in [`store/templates.yaml`](../store/templates.yaml)
and the checked-in font, writes the six PNGs Play accepts into
`build/store-assets/`, and validates every one of them against the current
Play requirements — a picture that Play would refuse fails the command instead
of reaching the upload. The set comes with a `manifest.json`, a `README.md` and
a `contact-sheet.png` (the whole set on one picture, to look at before an
upload). The story of the render is in [`store/README.md`](../store/README.md).

## Layout

```
Maestro/
├── config.yaml                 # workspace metadata
├── TAGS.md                     # complete catalogue of Maestro-addressable ids
├── helpers/                    # 21 subflows — never run standalone; the table is in CONVENTIONS.md
│   ├── launch-clean.yaml       # clean launch + wait for the shell (8s budget)
│   └── seed.yaml               # the deep-link contract: one entity per link
├── capture/                    # the Google Play screenshot storyboard — the dataset plus the shot list
│   ├── demo-dataset.yaml       # the fixed demo dataset behind the store screenshots
│   ├── STORYBOARD.md           # why each screenshot is there, and the capture profile
│   └── manifest.yaml           # the file names and the order
└── flows/
    ├── smoke/                  # 5 of the 27 must-pass flows; the tag `smoke` selects all 27
    ├── nav/                    # bottom-bar navigation
    ├── tasks/                  # task CRUD and the task editor attribute rows
    ├── agenda/                 # smart lists (Inbox buckets) + saved agenda views
    ├── pomodoro/               # timer: open, focus task, pause/resume, skip, stop
    ├── notes/                  # note list, editor, filter chips
    ├── projects/               # project list, detail, quick-add task
    ├── calendar/               # month view, view modes, tap-day
    ├── settings/               # Interface (theme), Notifications
    ├── profile/                # create a profile from the Menu sheet
    ├── sync/                   # offline create survives no connectivity
    ├── system/                 # shell-level chrome (menu sheet)
    └── capture/                # the store screenshots — one flow per storyboard shot, tags `manual` and `capture`
```

The subdirectory split mirrors `shared/src/commonMain/.../feature/`, so a flow
lives next to the feature it covers. New feature subdirectories are added as
their flows land — the plan for the remaining coverage (agenda, calendar,
search, pomodoro, settings-deep, sync, backup, ai, profile, auth, lifecycle,
negative, validation) lives in the Maestro UI test plan.

## Tags

| Tag | Meaning |
|---|---|
| `smoke` | P0 critical paths. The only tag CI gates on. |
| `regression` | P0 + P1. Full feature coverage. |
| `feature` | All feature flows (convenience alias). |
| `lifecycle` | Kill/restart, home-return, rotation. |
| `auth-live` | Auth flows that need the **real** provider, not the E2E stub. |

The `auth-live` tag is what keeps a flow out of the automated suites: the E2E
build replaces the auth repository with a stub, so an axis that needs a real
server (a network error, a rejected password from the provider) cannot happen
there. Such a flow carries `auth-live` instead of `auth`, and is run by hand
against a build with the real provider — `manual` says the same thing. A flow
that only needs the *stub's* behaviour (a flag file the stub reads, a client-side
validation error) belongs to `auth` and runs in CI.

Filter by feature through the directory path, not a separate tag:
`Maestro/flows/projects/` = all project flows.

## TestTag catalogue

Every Maestro-addressable `id:` comes from the **single source of truth**:

```
shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt
```

Dynamic tags (`task_item_<title>`, `note_item_by_title_<title>`,
`nav_tab_<title>`, `project_card_<name>`, `menu_<label>`,
`settings_tab_<name>`) are produced by functions — never hard-code
the expanded form.

The full catalogue including string values for each constant is in
`Maestro/TAGS.md`.

## Commands

Every top-level list item in a flow must name a real Maestro command
(`- tapOn:`, `- runFlow:`, etc.). `MaestroFlowTagsTest` enforces this in CI.

The canonical list lives in `KNOWN_COMMANDS` in `MaestroFlowTagsTest.kt`.
Two commands in use are not documented on maestro.mobile.dev/cli but are
shipped with the CLI binary and supported:

- **`exec`** — runs an arbitrary workstation command. Used in `auth/08-signup-network-error.yaml`
  and `auth/09-signin-network-error.yaml` to toggle airplane mode via `adb shell` for
  network-error simulation. Runs on the workstation, not the device.
- **`waitForAnimations`** — waits for all in-flight animations to settle.
  Used after `exec` calls that change system state, to let the device UI stabilise.

Adding a new command to a flow requires adding it to `KNOWN_COMMANDS` in the same PR,
otherwise `MaestroFlowTagsTest.every flow command is a real Maestro command` fails.

## Conventions

**Select on `id:`, not `text:`.** Every selector should be a `TestTags`
constant. A dynamic tag (`task_item_…`, `project_card_…`) is produced by
`TestTags.slug()`, which lowercases and replaces non-alphanumerics with `_`.

**Use ASCII test data.** Maestro's `inputText` cannot type non-ASCII on
Android. `slug()` keeps non-Latin titles addressable, but that is a safety
net, not an invitation — flows should type `Buy milk`, not `Купить хлеб`.

**Tag menu items by label, not destination.** `menuItem()` takes the visible
label so that two entries pointing at the same destination (`Profile & sync` and
`Settings`) keep distinct tags.

**Wait, don't sleep.** Use `extendedWaitUntil` with a bounded timeout. The
`launch-clean` helper allows 8s for cold start because Koin, Room, DataStore
migrations and the calendar sync orchestrator all initialise before the first
frame. Never use `sleep`.

**Hide the keyboard before tapping a trailing action.** `inputText` can leave
the IME up, covering the save button. Always follow `inputText` with
`hideKeyboard` — **once**. A `hideKeyboard` with nothing to hide is a BACK
keypress, and the editors are dialogs: BACK dismisses them, and the flow that
asked for it finds its save button gone. Measured on a real phone; see
`docs/decisions/2026-10-28-the-keyboard-was-already-down.md`.

**Assert the field value before tapping save.** Catch dropped keystrokes at
the real cause — the `assertVisible`/`inputText` round-trip.

**Never name a pixel — unless you say why.** A swipe to `105, 2000` is a
claim about the display it was measured on: on a 720×1600 phone the start
point is off-screen, the swipe never happens, and the flow fails on a screen it
never claimed to know about. A green run of that flow on the CI emulator is a
statement about the emulator. Address the gesture instead — a share of the
screen (`start: "50%, 88%"`) or the element that scrolls
(`swipe: {direction: UP, element: {id: menu_sheet}}`). A flow that cannot say
it any other way belongs in `config/docs/maestro-pixel-exceptions.txt`, with
the reason written down: the gate
`scripts/check-maestro-screen-geometry.py` fails on a pixel that nobody
explained, on an exception with no reason, and on an exception for a flow that
no longer needs it.

**Assert what the flow's own data puts on the screen.** A section header
renders only when the section has a task, and the runner wipes the content
before the flow, so an empty agenda has no section header to find: waiting for
one waits for nothing. Either put a task in the section first, or assert the
affordance the flow needs next. A task the flow seeded is such a task, so a
wait on the section that holds it is the seeded task's arrival — the rule is
written in `helpers/seed.yaml`.

**Put the tag on a node that survives the merge.** A Material3 `Card` is a
`Surface`, and a Surface merges its descendants: a tag below the card is
visible in the unmerged semantics tree only, where Maestro — which drives the
accessibility tree — cannot see it. A card that needs two identifiers (an id
*and* a title) puts the id on the card, which is the merged node, and the title
on the item's outer node.

## Adding a flow

1. Pick the subdirectory matching the feature; name it `<verb>-<object>.yaml`.
2. `runFlow: ../../helpers/launch-clean.yaml` first, unless you need existing
   data (`seed-task.yaml`; new flows should seed their own fixtures inline).
3. Prefer an existing `TestTags` constant. If the screen has no testTag:
   - Add a `const val` or `@Suppress("UnusedParameter") fun` to `TestTags.kt`
   - Add a `Modifier.testTag(...)` at the Composable call site
   - Regenerate `Maestro/TAGS.md`: `./gradlew :shared:jvmTest -PupdateGoldens=true`
     Then commit both files in the same PR — a stale TAGS.md breaks
     `TestTagsCatalogJvmTest` and the `validate-selectors` CI gate.
4. Tag it: always `feature`, plus `smoke` for P0 and `regression` for P1.
5. Run it: `just tm flow=Maestro/flows/<sub>/<file>.yaml`.
6. **Say whether it ran on a device.** A flow that has only been checked
   syntactically is not a flow that has been checked: the selector validator
   proves the ids exist, not that the screen is where the flow thinks it is.
   Write the line in the **header of the flow** — the file that outlives the
   pull request — and repeat it in the PR description:

   ```
   # прогнан на устройстве: да (2026-10-23)   |   нет (эмулятор был занят)
   ```

   "нет" is an honest answer and a useful one — it tells the next reader that
   the flow has not met a screen yet, and the header is where they look. "да"
   without a date is not an answer.
   A flow added to `smoke` has to have run on a device: a smoke flow that was
   never run is a smoke flow that fails in CI on the first evening it is
   allowed to fail.
7. On failure, read `~/.maestro/tests/<timestamp>/` — it holds the view
   hierarchy and screenshots for the failing step.

## Cold-start

If `launchApp` fails with `SerializerAlreadyRegisteredException`, see
`docs/decisions/2026-09-28-android-cold-start-nav3-serializer-crash.md`.
The workaround is verified; re-check before assuming a new failure is related.

## Related

- Skill: `.agents/skills/singularity-todo-maestro-flows/SKILL.md`
- Device pre-flight: `singularity-todo-adb-workflow` skill (wake, unlock, install,
  logcat triage) — the device itself comes from `ensure-emulator`
- Note: `singularity-todo-test-tag-strategy` covers JUnit `@Tag("slow")`,
  which is unrelated to Maestro's `tags:` header despite the similar name.
