---
title: "Single source of truth for app version, typed schema versioning, and runtime version gates"
date: 2026-09-23
tags: [versioning, schema, sync, genui, backup, security, kmp]
status: accepted
---

## Context

Four places in the codebase declare or use a version identifier but never enforce it:

1. **`SyncProtocol`** (`core/sync/SyncProtocol.kt:12,30`) — sends `protocolVersion: Int = 1` on every push, but the client never validates it. A server sending `protocolVersion = 2` is silently accepted.
2. **GenUI A2UI** (`feature/genui/`) — the version is only the string `"v0.9"` embedded in prompts. No `schemaVersion` field on events, no parse-time validation.
3. **Backup manifest** (`core/backup/BackupManifest.kt:9`) — stores `appVersion: String` but never enforces it. An old backup from a decommissioned app version can be restored into a newer client.
4. **Hardcoded `"0.1.0"`** in three places: `SingularityApp.kt:22` (Android), `desktopApp/main.kt:21` (JVM), `BackupOptions.kt:13,37` (default export version). These drift from `androidApp/build.gradle.kts:47` (`versionName`) and `desktopApp/build.gradle.kts:69` (`packageVersion`) on every release.

Additionally, `core/sync/RemoteConfig.kt` is named as if it were a feature-flag / remote-config store, but it holds only Supabase endpoint credentials. The name is a misnomer that confuses the codebase.

The article _"Нативные OTA-обновления в Android"_ establishes a layered model: Play In-App Updates → Remote Config → Remote Content → Server-Driven UI. The missing version gate (Layer 2) cannot work without a canonical, enforced app version.

## Idea

Apply the same pattern that `BackupManifest.schemaVersion` + `validateManifest()` already uses successfully:

- Every protocol/schema carries a `schemaVersion: Int`.
- The client defines `CURRENT_SCHEMA_VERSION = N` as a compile-time constant.
- On parse/apply: `if (incoming.schemaVersion > CURRENT) drop + warn log` — never crash, never accept.
- For the app version itself: a single `expect/actual fun appVersion(): AppVersion` as the canonical source, replacing all three hardcoded literals.
- A `RemoteConfigSnapshot` port that fetches, caches, and exposes `minSupportedVersion: AppVersion?` — the server can block old clients.
- Renamed `core/sync/RemoteConfig.kt` → `core/sync/SupabaseEndpointConfig.kt` to fix the misnomer.

## Decision

### App version: single source of truth

Add `expect fun appVersion(): AppVersion` to `core/version/AppVersion.kt` in commonMain:

```kotlin
data class AppVersion(val name: String, val code: Int) {
    operator fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.code })
}

expect fun appVersion(): AppVersion
```

**Android**: reads `BuildConfig.VERSION_NAME` and `VERSION_CODE` from `androidApp`'s generated `BuildConfig` class. File: `core/version/AppVersion.android.kt`.

**JVM/Desktop**: reads from system property `singularity.version` passed as `-Dsingularity.version=0.1.0` in the desktop app's `compose.desktop.application.jvmArgs`. Falls back to `"0.0.0"` if unset (developer mode). File: `core/version/AppVersion.jvm.kt`.

All three hardcoded `"0.1.0"` literals are replaced with `appVersion().name`.

### Typed remote config snapshot

`core/config/RemoteConfigSnapshot.kt` (commonMain):

```kotlin
data class RemoteConfigSnapshot(
    val schemaVersion: Int,
    val fetchedAt: Instant,
    val minSupportedVersion: AppVersion?,
    val maintenanceBanner: BannerDto?,
    val modelFlags: Map<String, Boolean>,
    val mcpToolFlags: Map<String, Boolean>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        fun validate(json: JsonObject): Result<RemoteConfigSnapshot>
    }
}
```

`RemoteConfigPort.kt` (commonMain):

```kotlin
interface RemoteConfigPort {
    suspend fun snapshot(): RemoteConfigSnapshot   // cache-first, last-known-good
    suspend fun refresh(): Result<RemoteConfigSnapshot>
    fun observe(): StateFlow<RemoteConfigSnapshot>
}
```

`RemoteConfigRepositoryImpl.kt`: new Room table `remote_config_cache(snapshot_json, fetched_at)`. TTL = 6 hours. On network failure → last cached → defaults. Storage is `remote_config_cache` entity, not `RemoteConfig` (see below).

### Min-version gate

When `appVersion() < minSupportedVersion` received from `RemoteConfigPort.observe()`: the app renders `AppVersionGateScreen` (full-screen, non-dismissable) with a button to open the Play Store (Android) or releases page (desktop). Gate is checked on every app start and after each `RemoteConfigPort.refresh()`.

### Schema version checks

| Layer | Constant | Check location | Behaviour on old |
|---|---|---|---|
| Sync | `SyncProtocol.CURRENT_PROTOCOL_VERSION = 1` | `SyncBootstrapper.handleEvent()` | drop + warn log |
| GenUI A2UI | `A2UI_CURRENT_SCHEMA_VERSION = 1` | `A2uiParser.parseLine()` | drop + warn log |
| Backup manifest | `BackupFormat.SCHEMA_VERSION = 1` | `BackupDomain.validateManifest()` | reject older, accept newer (forward-compat via `ignoreUnknownKeys`) |
| Remote config | `RemoteConfigSnapshot.CURRENT_SCHEMA_VERSION = 1` | `RemoteConfigSnapshot.validate()` | `Result.failure` |

### Rename Supabase credentials store (deferred to a cleanup MR)

The existing `core/sync/RemoteConfigRepository` / `RemoteConfigEntity` / `RemoteConfigDao` names are a misnomer — they store Supabase endpoint credentials, not remote config. This rename (to `SupabaseEndpointRepository` / `SupabaseEndpointEntity` / `SupabaseEndpointDao`) is a pure rename with no behaviour change and is deferred to a low-priority cleanup MR.

### Maintenance banner

`RemoteConfigSnapshot.maintenanceBanner: BannerDto?` is rendered as a dismissible top-banner in `SettingsScreen`. The banner is shown only when `maintenanceBanner != null` and `!isDismissed`. Dismissal is stored in `DataStore` (per-profile).

## Rationale

The `BackupManifest` pattern is already tested and proven in the codebase: `schemaVersion` field, `validateManifest()` with typed `Result`, forward-compat via `ignoreUnknownKeys`, backward-compat via migration map. We replicate it exactly rather than inventing a new one.

`appVersion()` as `expect/actual` is the only KMP-correct way: BuildConfig is Android-only, and there is no equivalent on the JVM target. The JVM target must receive the version from the host application at startup; a system property (`-Dsingularity.version`) is the standard JVM mechanism for this.

`Result<T>` for all validation functions (rather than throwing) is consistent with the codebase's error-handling style (`Result<T>`, not `throw`).

Silent drop + warn log (not crash, not silent accept) for old protocol versions is the correct behaviour: an old client should not crash because a newer server exists, but it should not process unknown fields either. The server's `error = "too_old"` in `SyncProtocol` handles the case where the client is truly incompatible.

## Consequences

- `appVersion()` is the **only** place that reads the running app's version. All other code — logging, backup manifest, About dialog — uses it. Version literals `"0.1.0"` must not be added anywhere else.
- `RemoteConfigPort.snapshot()` is the **only** write path to the local Room cache. No other code writes `remote_config_cache` directly.
- `RemoteConfigPort` is a stub in MR-2: `SyncApiClient.getRemoteConfig()` returns null, so `RemoteConfigRepositoryImpl` always falls back to defaults. Full backend implementation is deferred.
- `RemoteConfigSnapshot.validate()` is called **every time** a snapshot is deserialized from cache or network. Never skip validation.
- `SyncBootstrapper`, `A2uiParser`, and `BackupDomain` use **consistent** error-severity: `warn` for version mismatch, `error` for schema parse failure. This is reflected in log output and sync status.
- `core/sync/RemoteConfig` (Supabase credentials) is never to be confused with `RemoteConfigPort` (runtime policy). The former is a repository; the latter is a remote-gateway port.
- Kill switches (`modelFlags`, `mcpToolFlags`) are **compile-time safe**: `KnownModels` and `McpToolRegistry` accept `RemoteConfigSnapshot` as a parameter, never `RemoteConfigPort` directly. This enables testing with `FakeRemoteConfigSnapshot`.
- Schema versions (`protocolVersion`, `schemaVersion`) are **int**, not string. `"v0.9"` in GenUI prompts is descriptive only; it is never parsed or compared.

## Links

- Existing pattern: `core/backup/BackupManifest.kt`, `BackupDomain.validateManifest()`
- Play In-App Updates: `androidApp/.../AppUpdateGate.kt` (deferred to MR-8)
- ADR: `2026-09-22-system-calendar-sync.md` (schema versioning analogue)
