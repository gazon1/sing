# Bounded reads — the inventory of the unbounded ones

**Status:** living document. It names every place in production code where a read can
wait longer than the work it waits for, and says what was decided about each.

## What a bounded read is

`com.singularity.todo.core.coroutines.boundedRead` runs a read in a scope of its own and
lets the caller wait at most a deadline for its answer. The caller is released at the
deadline whether or not the read ever finishes; the read keeps running where it is, and
a failure it produces after the caller walked away is reported rather than dropped.

A deadline in the caller's own coroutine cannot do this. `withTimeoutOrNull` ends the
wait only if the read honours cancellation, and a read blocked inside a driver call — a
keychain read that never answers, a preferences file locked by another process, a Room
database created on first launch — ignores cancellation completely. The deadline is then
decoration, and the caller waits for the read anyway.

Two places use it today:

| Site | Bound |
|---|---|
| `core/auth/AuthRepository.kt` — the session restore | 10 s (`RESTORE_SESSION_TIMEOUT`) |
| `core/config/RemoteConfigCacheRepositoryImpl.kt` — the config snapshot and refresh | `RemoteConfigPort.READ_TIMEOUT_MS` |

## The rule this inventory follows

A read is **unbounded** when it waits for something that may never arrive, and the wait
has no deadline of its own. Three shapes count:

- a read of the first element of a flow — `first()`, `firstOrNull()`, `take(1)`,
  `single()`, `first { … }`;
- a read that waits for a condition — "until the counter passes this", "until the
  channel says so";
- a read whose source can block — a store, a database, a driver.

Two shapes do **not** count, and are listed here only so that their absence is a
decision rather than an oversight:

- **a continuous subscription** — `collect { }` in a view model's scope. It is not a
  read of an answer; it is the shape the data has. A deadline on it would end the
  subscription, not the wait.
- **a lookup in a collection** — `entries.firstOrNull { … }`, `list.first()`. Nothing
  waits; the collection is already in hand.

## How the inventory was made

Every `first()`, `firstOrNull()`, `first { … }`, `take(1)`, `single()` and
`singleOrNull()` in `shared/src/commonMain`, `shared/src/androidMain` and
`shared/src/jvmMain` was read, and each call was classified by what it waits for. The
`while (true)` loops in the tree are pure computation over a string or a list — the
tokenizer, the recurrence calculator, the GenUI schema walker — and none of them waits
for anything.

The counts below are call sites, not files.

## The inventory

### 1. Bounded, and why

**The session restore** — `core/auth/AuthRepository.kt`. The restore reads the
keychain and the preferences store, and both can block in a driver call. It runs in
`restoreScope`, a scope of its own, and the repository waits on a promise of its answer
(`boundedRead`, 10 s). The deadline is the user's answer, not the storage's: a store
that is unreachable means the login screen, and "Continue Offline" is one tap away.

**The remote config read and refresh** — `core/config/RemoteConfigCacheRepositoryImpl.kt`.
The same shape: a network call and a database read that can block, bounded by the port's
own timeout, with the last known good snapshot served when the deadline passes.

**The restore failure stream** — `core/auth/AuthRepository.kt`. This one was unbounded
until this change, and it is the only read in the tree that could wait for something
that could never arrive.

The failure is published through a buffered channel, so a reader that arrives after the
restore still finds the report. But the channel was never closed: a reader that asked
"was there a restore failure?" and found nothing waited for a report that could not
exist, for as long as it cared to wait. The view model does exactly that — its
`restoreFailures.firstOrNull()` sat in the scope of the screen for the life of the
screen.

The restore runs once per repository instance and the repository is a process-lifetime
singleton, so the report can be published at most once and only by the restore. The
channel is now closed when that restore is over, and a closed channel with a buffered
element delivers that element first and completes afterwards. The absence of a failure
is now something a reader is told, and the wait is bounded by the restore — which is
itself bounded.

### 2. Reads whose source always answers, and why they are left alone

These read a source that has an answer ready: a `StateFlow` always has a value, a
DataStore always has its current preferences, a Room query always has the current
contents of its tables. The read returns on the first emission.

| Kind | Where |
|---|---|
| `StateFlow.value` | `core/di/CoreDiModule.kt:413`, `core/sync/OutboxService.kt:62`, `core/sync/PushPhase.kt:248`, `core/sync/SyncEngine.kt:461` |
| DataStore preferences | `core/auth/DataStoreSessionStore.kt:89,120,144`, `core/auth/SecureSessionStore.kt:124-126`, `core/draft/DataStoreDraftStore.kt:31`, `core/settings/SettingsDataStoreMigration.kt:121,124`, `core/settings/AiApiKeyMigration.kt:36`, `core/sync/SyncPrefs.kt:71`, `feature/whatsnew/presentation/WhatsNewPrefs.kt:42` |
| Settings preference flows | `core/llm/OpenAiConfig.kt:46-49`, `feature/ai/KoogAgentService.kt:121-145`, `feature/agenda/presentation/viewmodel/AgendaViewModel.kt:188-189`, `core/work/ArchiveJob.kt:24`, `core/work/BackupJob.kt:18`, `core/ui/onboarding/OnboardingSettingsRepository.kt:49`, `core/ui/onboarding/SpotlightTourController.kt:93` |
| Room queries | `core/sync/SeedPlanner.kt:130-136`, `feature/ai/tools/GetTaskTool.kt:26`, `feature/ai/tools/ListTasksTool.kt:25`, `feature/ai/tools/ListProjectsTool.kt:30`, `feature/ai/tools/ListLinkedTasksTool.kt:21`, `feature/ai/tools/SearchTasksTool.kt:20`, `feature/ai/tools/UpdateTaskTool.kt:39`, `feature/checklist/data/ChecklistRepositoryImpl.kt:35,65,73,88`, `feature/profile/ProfileBootstrapper.kt:54,57`, `feature/projects/domain/usecase/DeleteProject.kt:21`, `feature/tasks/data/TaskRepositoryImpl.kt:266,269,355,402`, `feature/tasks/domain/logic/DependencyValidatorImpl.kt:60,96,134` |

**The honest caveat.** "Always answers" is a property of the source, not a guarantee
about the device. A preferences file locked by another process, or a database opened by
a second instance, can block the read that was supposed to answer — the same hazard the
restore was given a deadline for. These sites are therefore *not* proof that they cannot
hang; they are places where the deadline has not been applied.

They are left alone here for two reasons.

1. **A deadline in the caller's coroutine would not be a deadline.** The bound that
   works is `boundedRead` in a scope of its own, and that scope has to be a property of
   the component that owns the read — a repository, a use case, a worker. Applying it
   to two dozen call sites is a change of that shape in each of them, each with its own
   deadline to choose and its own abandoned-read failure to report. That is a campaign,
   not a change, and the deadlines it would need are a decision each site has to make
   for itself.
2. **Most of these reads are not on a path a user is waiting on.** A read that answers
   in microseconds does not need a deadline; a read that blocks for a second inside a
   background job does not strand a user either. The restore was given a deadline
   because it stands between the user and the app's first screen.

The rule to apply when one of these is next touched: if the read is on a path the user
waits on, give it a scope of its own and a deadline; if it is not, say so in the code
next to it, so the decision is written down rather than inherited.

### 3. Reads that wait for a condition, and why they are left alone

**The sync cycle** — `core/sync/SyncCoordinator.kt:103`. `completedCycles.first { it > seen }`
waits for the next completed sync cycle. It is unbounded on purpose: the caller asked
for a cycle, and the answer is the cycle. There is no deadline that would be honest
here — a sync cycle on a slow network can take minutes, and a deadline would turn "the
sync is still running" into "the sync did not happen".

What keeps it from being a silent hang is an explicit release: `close()` publishes
`Long.MAX_VALUE`, which no counter can reach, so every waiter is released with
`Skipped` rather than left suspended. The wait ends when the sync engine is closed, and
not before — which is the correct answer for a component whose whole job is to wait for
the next cycle.

### 4. Not reads at all

`entries.firstOrNull { … }` over an enum, `list.first()` over a collection already in
hand — these are lookups, they cannot wait, and they are not part of this inventory.
They are listed here because a grep for `first` finds dozens of them, and a reader who
sees them in a scan should know they were looked at and dismissed.

## What a follow-up would look like

The inventory is complete; the *work* is not. Two things would move it:

1. **A gate.** A check that fails when a flow read appears without a deadline would
   keep the inventory from growing backwards. The rule it would have to encode is the
   awkward one: a deadline in the caller's own coroutine is not a deadline, so the gate
   would have to ask for a scope of its own, not for a `withTimeout`.
2. **A deadline per site, chosen by the site.** The bound that is right for a read of
   the settings on a background job is not the bound that is right for a read of the
   keychain in front of the login screen. This document cannot choose them, and a
   single constant for all of them would be a number nobody could explain.
