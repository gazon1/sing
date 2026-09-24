# Sync

Offline-first sync engine using Supabase REST API + HLC (Hybrid Logical Clock) timestamps. Conflict resolution via Last-Write-Wins per field with remote precedence on tie.

## Structure

```
core/sync/
├── SyncEngine.kt        Main orchestrator: pull → resolve → push cycle
├── SyncRunner.kt        Coroutine-based runner with exponential back-off
├── SyncRepository.kt    Interface: SyncableEntity, last sync timestamps
├── SyncRepositoryImpl.kt Room-backed sync state
├── SyncApi.kt           Supabase REST client (HTTP)
├── SyncOutbox.kt        Pending outbound changes queue
├── SyncTrigger.kt       Network + timer-based trigger logic
├── SyncBootstrapper.kt Initial sync on profile creation
├── ConflictResolver.kt  Field-level LWW conflict resolution
├── Hlc.kt               Hybrid Logical Clock implementation
├── HlcFactory.kt        Hlc instance factory
├── SyncPrefs.kt         DataStore-backed sync preferences
├── SyncStatus.kt        Sealed UI state (Idle, Syncing, Error)
├── SyncFormEvent.kt     One-shot sync events (SyncCompleted, SyncFailed)
├── AutoSync.kt          Auto-sync configuration
├── RemoteConfig.kt      Remote feature flags + OTA config
└── work/                Background work (coroutine dispatchers)
```

## Key entry points

| What | Where |
|---|---|
| Engine | `SyncEngine` (interface), `SyncEngineImpl` |
| Repository | `SyncRepository` (interface), `SyncRepositoryImpl` (Room) |
| API client | `SyncApi` (interface), `SupabaseSyncApi` |
| HLC | `Hlc`, `HlcFactory` |

## Conflict resolution

1. Pull remote changes
2. For each local entity: compare `hlc` timestamps field-by-field
3. Remote wins on equal timestamps (remote precedence)
4. Push resolved state

## Relevant ADRs

- `docs/decisions/2026-09-05-sync-conflict-resolution.md` — LWW + remote precedence
- `docs/decisions/2026-09-05-hlc-implementation.md` — HLC design
- `docs/decisions/2026-09-05-supabase-rest-api.md` — why REST not realtime
