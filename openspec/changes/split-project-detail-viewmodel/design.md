# design — split `ProjectDetailViewModel`

## Summary

Extract two concerns from `ProjectDetailViewModel` into collaborators that hold
**no state flows**, leaving the ViewModel as a state machine plus an intent
dispatch table. The screen's single-atomic-snapshot contract is preserved
(AC-02), so this is deliberately *not* a sub-ViewModel split.

## Current architecture and affected modules

`shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt`, 573 lines, 13 constructor parameters, 13 intents.

The file is organised in four interleaved regions:

| Region | Lines | Concern |
|---|---|---|
| State slots | 98–208 | 11 `MutableStateFlow`/`StateFlow` fields, all read only by the combines |
| Collector wiring | 210–378 | ~10 `vmScope.launch` blocks feeding those flows, then two 5-way `combine`s |
| Intent dispatch | 409–493 | 13 branches, mixing local-flow mutation, use-case calls, and debounced writes |
| Write helpers | 496–559 | `mutate`, `setReminder`, `blockedIds` |

Two 5-way `combine`s sit at `:315-356` and `:357-377`. `ProjectDetailViewModel.kt:305-307`
records that kotlinx ships typed `combine` overloads only up to five flows, and a
vararg call would collapse to `Array<Any?>`. Two separate comments (`:112-115`,
`:181-187`) exist purely to work around that ceiling — the reminder pair was
folded into one `ReminderOffsets` value and the two visibility toggles into one
`TaskVisibility` value, both only to fit.

Only two call sites construct this VM: `ProjectsDiModule.kt:59-75` and
`ProjectDetailViewModelTest.kt`. The blast radius is therefore small.

## Proposed solution

**Extract derivation and write-logic. Keep state ownership in the ViewModel.**

Two collaborators, both state-free:

1. **`ProjectReminderCoordinator`** — owns everything reminder: the default-offset
   lookup, the existing-reminder watch, `fireAt` computation, and the upsert /
   remove write. Today this is scattered across `:168-199` (flows),
   `:232-249` (two collectors), `:357-377` (combine slot) and `:531-559`
   (`setReminder`) — one concern in four places. It takes
   `ProjectRemindersRepository`, `NotificationsSettingsRepository`, `projectId`
   and a `Flow<Project?>` for the project, and exposes
   `offsets(): Flow<ReminderOffsets>` plus `suspend fun set(offsetMinutes: Int?)`.

2. **`ProjectTaskListDeriver`** — a **pure** object with no coroutines and no
   repository: `blockedIds(tasks)`, `visible(tasks, visibility, blockedIds)`,
   `children(projects, projectId)`, and the `take(5)` + sort projection. Today
   this logic is inlined inside collectors at `:135` and `:269-279`, which is why
   none of it is directly testable without driving a ViewModel.

The ViewModel keeps: the state slots, both `combine`s, the intent table, the
debouncers, and `mutate`.

### Why these two and not others

Each extraction removes a **named** defect:

- **Reminder** — the `fireAt = dueDate − offset` invariant is currently expressed
  in a collector lambda rather than named anywhere. Four tests assert it
  indirectly, through a ViewModel, for a rule about two fields. After extraction
  the rule is a named function with those same four tests pointed at it
  directly.
- **Task list** — derivation is pure and therefore currently untestable in
  isolation: `ProjectDetailViewModelTest` needs a whole VM, a scope and fake
  repositories to assert a list filter. After extraction it is a function call.

## Alternatives considered

**A. Full sub-ViewModels, mirroring `AttachmentsViewModel`.** Rejected. That
precedent works because attachments are an independent panel with its own
lifecycle. Here, `ProjectDetailScreen`'s `Content` is assembled by the combines
from five inputs; giving state to sub-ViewModels means the screen either
collects several streams — directly violating AC-02 and the stated intent at
`:100-105` — or the parent still recombines them, in which case the sub-VMs buy
nothing but an extra layer.

**B. Mirror the `SettingsContributor` pattern.** Rejected on a factual
difference: settings contributors are *stateless transformers* that own no flows
(the store owns state, `SettingsContributor.kt:20-23`), and each of the seven
settings sections is independent, so the VM holds seven independent slots and
never combines them. `SettingsViewModel` is 246 lines partly *because* nothing
needs combining. `ProjectDetailUiState.Content` is the opposite case — one
combined value — so the pattern does not transfer, and copying it would be the
"proposing an abstraction because it is theoretically general" failure.

**C. Leave it.** Rejected. The five-argument `combine` ceiling has already forced
two workarounds into the file (`:112-115`, `:181-187`), and the next feature
area added will force a third or a vararg.

## Why this solution is the simplest

It moves code without moving state. State slots, the combines, the intent
hierarchy and the screen contract all stay exactly where they are, so AC-01
through AC-03 are satisfied by construction rather than by testing — there is no
new way for the emitted state to differ. `ProjectDetailDraftState` is the
existing in-file precedent for this shape of extraction: a concern pulled into
its own file, with the VM still owning when it is consulted.

## Component and dependency changes

| Change | Defect it removes |
|---|---|
| New `ProjectReminderCoordinator` (state-free) | A reminder invariant spread over four regions and asserted only through a ViewModel |
| New `ProjectTaskListDeriver` (pure) | Pure list logic reachable only by constructing a ViewModel with a scope and fakes |
| Remove `log: Logger` parameter | A dependency DI supplies and the file never reads (AC-07) |
| Remove the `log = Logger.withTag("ProjectDetail")` argument in `ProjectsDiModule.kt:72` | — |

No new interface, no framework component, no pattern. Two concrete classes.

## State-model changes

None. Every `MutableStateFlow` keeps its owner, its type and its writer. The
`ReminderOffsets` and `TaskVisibility` packing values survive as-is; they exist to
respect the five-argument `combine` ceiling, and this change does not alter how
many flows each combine takes.

## Persistence and compatibility impact

None. No Room change, no `SCHEMA_VERSION`, no `AutoMigration`. No state is
persisted by this refactor.

## Testing strategy

`ProjectDetailViewModelTest` (570 lines, 19 tests, `@Tag("fast")`) must pass
**unmodified** throughout. That is the primary safety net: it already covers
visibility toggles, project writes, delete, create-task, subscription stability,
and all six reminder cases.

Added by this change:

- A focused unit test for `ProjectTaskListDeriver` covering hide-completed,
  hide-blocked, dependency-blocking, the `take(5)` cap and the sort — previously
  reachable only by driving the whole ViewModel.
- A focused unit test for the reminder offset rule (`fireAt = dueDate − offset`,
  null removal, no-due-date rejection).

Both are pure/fake-only, `@Tag("fast")`, and live in `shared/src/commonTest` —
no ViewModel, no scope, no `delay`.

### `log` removal

No test proves a parameter is unused, so this is verified by the DI graph
compiling and by grep: `log` must not appear in the VM file or in
`ProjectsDiModule.kt`'s binding.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Silently changing emitted state while moving a collector | AC-01; the existing 19 tests run unmodified at every task boundary |
| The reminder extraction drops `notificationsSettings` timing — the default must preselect, never create | AC-04's "default does not create" test is explicitly one of the six |
| Extracting the task list changes evaluation order and so the sort's stability | AC-05 plus the existing `totalCount` / `completedCount` assertions |
| `combine` arity creep | Deliberately unchanged; re-evaluating the arity problem is a separate change |

## Task dependencies

1. `ProjectTaskListDeriver` first — pure, no DI, no coroutines, independently
   testable.
2. `ProjectReminderCoordinator` second — needs the deriver's absence confirmed
   only in the sense that it touches a different file.
3. `log` removal last — a one-line cleanup that must not be bundled with the
   moves, so a regression points at a single cause.

1 and 2 touch disjoint files and may run in parallel. 3 must follow both.

## Non-goals

Listed in `spec.md`. The most important: no behavioural change, and
`UpdateInheritedTagGroups`' dual write is untouched.

## Validation commands

```bash
# The suite that must pass unmodified throughout
./gradlew :shared:jvmTest --tests 'com.singularity.todo.feature.projects.ProjectDetailViewModelTest'

# Full JVM suite + tags, as CI runs it
./gradlew :shared:jvmTest -Ptest.tags=fast,slow

# Lint, including the project's own custom rules
just lint

# The gate that would catch a sub-VM that nothing calls
python3 scripts/find-unwired-surfaces.py

# The full local gate suite
./check.sh
```