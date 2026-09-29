---
title: "SyncConfigScreen is never rendered — the planned sync flows have nothing to drive"
date: 2026-09-29
status: accepted
tags: [sync, ui, gap, maestro]
---

## Context

PR-4 was planned to cover Sync configuration — auto-sync toggle, interval slider,
"sync now", "test connection" — the screen the test plan called
`Maestro/flows/sync/{open-config,toggle-auto,change-interval,sync-now}.yaml`.

`SyncConfigScreen` exists (`feature/sync/presentation/SyncConfigScreen.kt`), and
its ViewModel is fully implemented: `SyncState` carries `autoSyncEnabled`,
`intervalMinutes`, `lastSyncedAt`, `isTestingConnection` and `connectionTestResult`,
and `SyncViewModel` handles `SyncNow`, `SetAutoSync`, `SetInterval`,
`TestConnection` and `AcknowledgeError`. A `SyncButton` composable exists too.

Nothing composes the screen. Grepping for `SyncConfigScreen` outside its own file
returns only KDoc references in `core/sync/ConnectionResult.kt` and
`core/sync/SyncFormEvent.kt` — no `entry { }`, no call site, no menu item. It is
not in the `SettingsTab` enum either, so it is not reachable by any navigation
path at all.

So the four planned sync-config flows have no target. The sync *engine* is
reachable — it is what makes the app local-first — and that is what
`sync-create-offline` covers: a task created with no connectivity is kept in
Room and is still there once the network returns.

## Idea

1. Wire `SyncConfigScreen` into a reachable place and write the four flows.
2. Cover the sync behaviour that is reachable (offline create) and record that
   the config screen has no host.
3. Write flows that navigate somewhere and assert nothing about sync.

## Decision

We did (2) for this PR.

`Maestro/flows/sync/01-create-offline.yaml` is the flow that matters and that
users feel: create a task in airplane mode, return online, find it still there.
It carries `tags: [regression, sync]` so it is discoverable under that name even
though the config screen is not covered.

(1) is a product change — deciding *where* sync settings belong (a new Settings
tab, or inside `Account`) — not a test-suite change, and it needs a decision
about the navigation model first. (3) would produce green tests that prove
nothing, which is worse than an acknowledged gap.

## Consequences

- `sync/open-config`, `toggle-auto`, `change-interval` and `sync-now` cannot be
  written until the screen has a host. This ADR is the record of that.
- The sync *engine* is untested end to end: `sync-create-offline` proves data
  survives being offline, not that it is ever pushed or pulled. A round-trip
  test needs a server or a fake transport.
- `SyncConfigScreen` and `SyncViewModel` are reachable-looking dead code — both
  fully implemented, neither called. A lint rule for "composable never invoked"
  would catch this class of gap; nothing in the current quality tooling does.
- This is the third instance of the same shape after the notes row actions and
  the Calendar header controls, which suggests it deserves a standing check
  rather than a one-off ADR.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/sync/presentation/SyncConfigScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/sync/presentation/SyncViewModel.kt`
- `Maestro/flows/sync/01-create-offline.yaml`
- Same pattern: `2026-09-29-notes-and-calendar-unreachable-controls.md`
- Related: `2026-09-29-missing-koin-dao-bindings.md` (also "declared but unreachable")
