# Maestro UI flows

End-to-end UI tests for the Android app, driven by [Maestro](https://maestro.mobile.dev)
against a real device or emulator.

## Status

The flows are written and the runner is wired up, but **they cannot execute yet**:
the app currently crashes on Android cold start with
`SerializerAlreadyRegisteredException` in `navSavedStateConfig`, so no flow gets
past `launchApp`. See
`docs/decisions/2026-09-28-android-cold-start-nav3-serializer-crash.md`.

Once that crash is fixed, the suite runs as-is — no YAML changes expected.

## Running

```bash
just tm                                   # every flow
just tm tags=smoke                        # only the smoke subset
just tm flow=Maestro/flows/tasks          # one directory
SERIAL=emulator-5554 just tm tags=smoke   # pin the device

# As part of the local check (opt-in; needs maestro CLI + a booted device):
RUN_MAESTRO=1 ./check.sh
```

Environment variables understood by `scripts/run-maestro.sh`:
`SERIAL`, `FLOW`, `TAGS`, `SKIP_INSTALL=1`.

## Layout

```
Maestro/
├── config.yaml                 # workspace metadata
├── helpers/                    # subflows — never run standalone
│   ├── launch-clean.yaml       # clean launch + wait for the shell
│   └── seed-task.yaml          # create one task on Today
└── flows/
    ├── smoke/                  # must-pass subset, runs in ~2-3 min
    ├── nav/                    # bottom-bar navigation
    ├── tasks/                  # task CRUD
    ├── projects/
    ├── notes/
    └── system/                 # shell-level chrome (menu sheet)
```

The subdirectory split mirrors `shared/src/commonMain/.../feature/`, so a flow
lives next to the feature it covers.

## Tags

| Tag | Meaning |
|---|---|
| `smoke` | Fast, must-pass subset. The only tag CI would ever gate on. |
| domain tag (`tasks`, `notes`, …) | Feature-scoped reruns during development. |

## Conventions

**Select on `id:`, not `text:`.** Every selector should be a `TestTags`
constant. The source of truth is
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`; a
dynamic tag (`task_item_…`, `project_card_…`) is produced by
`TestTags.slug()`, which lowercases and replaces non-alphanumerics with `_`.

**Use ASCII test data.** Maestro's `inputText` cannot type non-ASCII on
Android. `slug()` keeps non-Latin titles addressable, but that is a safety net,
not an invitation — flows should type `Buy milk`, not `Купить хлеб`.

**Tag menu items by label, not destination.** `menuItem()` takes the visible
label so that two entries pointing at the same destination (`Profile & sync` and
`Settings`) keep distinct tags.

**Wait, don't sleep.** Use `extendedWaitUntil` with a bounded timeout. The
`launch-clean` helper allows 8s for cold start because Koin, Room, DataStore
migrations and the calendar sync orchestrator all initialise before the first
frame.

**Hide the keyboard before tapping a trailing action.** `inputText` can leave the
IME up, covering the save button.

## Adding a flow

1. Pick the subdirectory matching the feature; name it `<verb>-<object>.yaml`.
2. `runFlow: ../../helpers/launch-clean.yaml` first, unless you need existing data.
3. Prefer an existing `TestTags` constant. If the screen has no testTag, add one
   to `TestTags.kt` and a `Modifier.testTag` at the call site — do not select by
   visible text as a shortcut.
4. Tag it: always a domain tag, plus `smoke` if a failure here should be loud.
5. Run it: `just tm flow=Maestro/flows/<sub>/<file>.yaml`.
6. On failure, read `~/.maestro/tests/<timestamp>/` — it holds the view hierarchy
   and screenshots for the failing step.

## Related

- Skill: `.agents/skills/singularity-todo-maestro-flows/SKILL.md`
- Device pre-flight: `singularity-todo-adb-workflow` skill (adb discovery, wake,
  unlock, install, logcat triage)
- Note: `singularity-todo-test-tag-strategy` covers JUnit `@Tag("slow")`, which
  is unrelated to Maestro's `tags:` header despite the similar name.
