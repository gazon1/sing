---
title: "Tag registry: один источник истины, и почему нет ProjectsRobot"
date: 2026-10-02
status: accepted
tags: [testing, maestro, testtags, ci, gates]
---

# Tag registry: one source of truth, and why there is no ProjectsRobot

## Context

The previous MR (`2026-10-01-test-infra-followups.md` §3) recorded a "52-ID gap"
between the Maestro tag gate and `TestTags.kt`, and proposed collapsing it.
While doing that work the gap turned out to be a symptom of something else
entirely: `Maestro/scripts/check-tags.sh` never read `TestTags.kt` at all.

## 1. The gate never read the registry it claimed to validate against

The script's header said it "validates every `id:` selector in Maestro YAML
flows against the TestTags.kt registry". The body defined `TESTTAGS_FILE`,
`ALLOW_PATTERNS` and `LEGACY_RAW` — and then used **none of them**. The entire
check was a 110-entry `if [[ "$id" == ... ]]` chain written by hand.

Consequences, in order of severity:

- **Renaming a tag could not fail it.** The chain was the source of truth, so
  `TestTags.kt` and the gate could disagree indefinitely with no signal.
- **Nothing ran it.** `grep -rn check-tags` across CI, `check.sh`, the
  Justfile and `.githooks/` returned only the script's own self-references. It
  was not wired into any gate. This is the project's own documented defect
  class — a surface that exists, is tested by hand, and is called by nobody —
  and it is the reason the list drifted in the first place.

## Decision

Derive the valid set from `TestTags.kt` at run time: exact `const val` literals
plus the literal prefix of every dynamic function. Delete the chain.

`fun profileItem(name) = "${PROFILE_ITEM_PREFIX}${slug(name)}"` needs care: its
body starts with an interpolation, so the literal prefix is `""`, and
`startswith("")` is true for every string. Accepting that would make the gate
pass everything — a vacuous green, the exact failure mode
`build-version-catalog-gate.py` guards against with `MIN_SCAN`. The parser
resolves such bodies by looking the referenced constant up by name
(`PROFILE_ITEM_PREFIX` → `profile_item_`), and asserts that no empty prefix
survives.

Result: **110 hand-maintained entries → 0.** All 94 ids across every flow are
derivable. The three `LEGACY_RAW` entries turned out to be plain `const val`s
already, so the escape hatch is empty and documented as debt that should not
grow.

### Positive controls (all four verified, not asserted)

| Scenario | Expected | Actual |
|---|---|---|
| Unknown id added to a flow | exit 1 | exit 1, names the id |
| Rename a `const` a flow depends on | exit 1 | exit 1, names the id |
| Dynamic function loses its prefix | exit 1, not pass-all | exit 1 — exactly the 9 `menu_*` ids fail |
| Fewer than 20 ids collected | exit 2 | exit 2 |

The second row is the regression the old chain structurally could not detect.

## 2. A second flow asserted a tag the UI never renders

`Maestro/flows/pomodoro/01-open-tab.yaml` waited on
`pomodoro_play_pause_button`. No such constant exists; `PomodoroScreen` renders
`TestTags.Pomodoro.PLAY_BUTTON` when paused, which is the state a fresh timer is
in. Same class as the `task_action_mark_completed` bug from the previous MR:
the assertion targeted a tag that no node ever had, so it proved nothing.
Changed to `pomodoro_play_button`.

Worth noting how it survived: `check-tags.sh` had it on the allow-list, so the
gate confirmed it. A gate that only checks its own list cannot find its own list
wrong.

## 3. There is no ProjectsRobot / NotesRobot / TagsRobot, and adding them would be a defect

The follow-up proposed generalising `TasksRobot` to the other domains. The
premise does not hold:

- **No flow test seeds through a repository.** All 11 `*FlowTest.kt` were
  checked; the count of `upsert(` calls outside the robot is **zero**.
- **Every robot call site is a task test** — `SetDueDate`, `SetPriority`,
  `TaskRow`, `AgendaTabDefinition` — and all four need *pre-existing* tasks
  with specific due dates before the screen opens.
- **`ProjectsFlowTest` and `NotesFlowTest` create their entities through the
  UI** (tap FAB → type name → save), because creation is the behaviour under
  test. A `ProjectsRobot.given(name)` would replace a UI interaction that is the
  assertion with a repository write that is not — it would *reduce* coverage.

`TasksRobot` exists because the agenda is the one screen that must render data
seeded before the test starts. That is a property of that screen, not a missing
symmetry across domains. Three robot classes with zero call sites would be the
unwired-surface defect this project already tracks with
`scripts/find-unwired-surfaces.py` — the fix would have introduced the disease.

Recorded here so the proposal is not re-raised. The trigger that would change
this answer: a notes or projects flow that needs the entity to exist *before*
the screen opens (a filter test, a sort test, a count badge).

## 4. The gate is now wired in, and runs before Gradle

Unwired was the original defect, so wiring is part of the fix, not an extra:

- `.githooks/pre-push` — before the Gradle suite. Both structural gates are
  file scans with no JVM, so a violation surfaces in under a second instead of
  after a multi-minute build.
- `check.sh` step `[2/6]`.
- `.github/workflows/ci.yml`.

## Open question for the owner

Every gate in `ci.yml` carries `continue-on-error: true`, so none of them —
including `find-unwired-surfaces.py` and the version-catalog gate — actually
block a merge. This MR follows the existing convention rather than flipping it
unilaterally, because turning advisory gates into blocking ones is a policy
decision that will surface whatever backlog has accumulated. Recorded in
`deferred-backlog.md` for a deliberate decision.

## Consequences

- A tag rename in `TestTags.kt` fails locally in `pre-push` and in `check.sh`,
  not on a device during a Maestro run.
- The registry is the only place a valid tag can be added; the gate cannot
  disagree with it.
- The empty-prefix assertion and `MIN_IDS` floor are what keep "no violations"
  from meaning "the parser broke".
- `continue-on-error` still means CI will not block on this until that policy
  changes.

## Links

- `2026-10-01-test-infra-followups.md` §3 — the original (smaller) framing
- `Maestro/scripts/check-tags.sh`
- `scripts/find-unwired-surfaces.py` — the same defect class, other instance
