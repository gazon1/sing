# Maestro UI flows

End-to-end UI tests for the Android app, driven by [Maestro](https://maestro.mobile.dev)
against a real device or emulator.

## Running

```bash
just tm                                   # every flow
just tm tags=smoke                        # only the smoke subset
just tm flow=Maestro/flows/tasks          # one directory
SERIAL=emulator-5554 just tm tags=smoke # pin the device

# As part of the local check (opt-in; needs maestro CLI + a booted device):
RUN_MAESTRO=1 ./check.sh
```

Environment variables understood by `scripts/run-maestro.sh`:
`SERIAL`, `FLOW`, `TAGS`, `SKIP_INSTALL=1`.

## Layout

```
Maestro/
├── config.yaml                 # workspace metadata
├── TAGS.md                     # complete catalogue of Maestro-addressable ids
├── helpers/                    # subflows — never run standalone
│   ├── launch-clean.yaml       # clean launch + wait for the shell (8s budget)
│   └── seed-task.yaml          # create one task titled "Buy milk"
└── flows/
    ├── smoke/                  # must-pass subset (8 flows, ~3.5 min)
    ├── nav/                    # bottom-bar navigation
    ├── tasks/                  # task CRUD and editor flows
    ├── notes/                  # note list, editor
    ├── projects/               # project create
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
`hideKeyboard`.

**Assert the field value before tapping save.** Catch dropped keystrokes at
the real cause — the `assertVisible`/`inputText` round-trip.

## Adding a flow

1. Pick the subdirectory matching the feature; name it `<verb>-<object>.yaml`.
2. `runFlow: ../../helpers/launch-clean.yaml` first, unless you need existing
   data (`seed-task.yaml`; new flows should seed their own fixtures inline).
3. Prefer an existing `TestTags` constant. If the screen has no testTag, add
   one to `TestTags.kt` and a `Modifier.testTag` at the call site — do not
   select by visible text as a shortcut.
4. Tag it: always `feature`, plus `smoke` for P0 and `regression` for P1.
5. Run it: `just tm flow=Maestro/flows/<sub>/<file>.yaml`.
6. On failure, read `~/.maestro/tests/<timestamp>/` — it holds the view
   hierarchy and screenshots for the failing step.

## Cold-start

If `launchApp` fails with `SerializerAlreadyRegisteredException`, see
`docs/decisions/2026-09-28-android-cold-start-nav3-serializer-crash.md`.
The workaround is verified; re-check before assuming a new failure is related.

## Related

- Skill: `.agents/skills/singularity-todo-maestro-flows/SKILL.md`
- Device pre-flight: `singularity-todo-adb-workflow` skill (adb discovery,
  wake, unlock, install, logcat triage)
- Note: `singularity-todo-test-tag-strategy` covers JUnit `@Tag("slow")`,
  which is unrelated to Maestro's `tags:` header despite the similar name.
