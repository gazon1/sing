# Maestro UI flows

End-to-end UI tests for the Android app, driven by [Maestro](https://maestro.mobile.dev)
against a real device or emulator.

## Running

```bash
just tm                                   # every flow
just tm tags=smoke                        # only the smoke subset
just tm flow=Maestro/flows/tasks          # one directory
SERIAL=emulator-5554 just tm tags=smoke # pin the device (recovery still runs)

# As part of the local check (opt-in; needs maestro CLI + a booted device):
RUN_MAESTRO=1 ./check.sh
```

Environment variables understood by `run-maestro`:
`SERIAL`, `FLOW`, `TAGS`, `SKIP_INSTALL=1`, `MAESTRO_MAX_RETRIES` (default 1).

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

## Layout

```
Maestro/
├── config.yaml                 # workspace metadata
├── TAGS.md                     # complete catalogue of Maestro-addressable ids
├── helpers/                    # subflows — never run standalone
│   ├── launch-clean.yaml       # clean launch + wait for the shell (8s budget)
│   └── seed-task.yaml          # create one task titled "Buy milk"
└── flows/
    ├── smoke/                  # must-pass subset (12 flows, ~4 min)
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
    └── system/                 # shell-level chrome (menu sheet)
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
affordance the flow needs next.

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
