---
title: "OTA Update Strategy — Play In-App Updates + Remote Config"
status: accepted
---

# OTA Update Strategy — Play In-App Updates + Remote Config

## Context

The app needs a mechanism to enforce minimum version requirements and push update prompts to users without relying solely on the app store's强制更新流程. Three layers of the OTA strategy from the apptractor.ru article are relevant:

1. **Play In-App Updates** (this ADR) — Android only; flexible update flow triggered by Play Core library
2. **Remote Config / Feature Flags** (already implemented: `RemoteConfigPort`, `RemoteConfigSnapshot`)
3. **Server-Driven Content / UI** (already implemented: `WhatsNewScreen` via GenUI)

The remaining layer — Server-Driven Content (static remote content) — is deferred because the app has no marketing surfaces that need it.

## Decision

### Layer 1: App Version Gate

`AppVersionGateScreen` renders **before** the main nav graph. It observes `RemoteConfigPort.observe().minSupportedVersion` and blocks the user with a store link if `appVersion() < minSupportedVersion`. This is a hard gate — no navigation is possible until the user updates.

Platform-specific `onOpenStore`:
- Android: `market://details?id=com.singularity.todo`
- Desktop: `https://github.com/singularity-todo/singularity/releases`

### Layer 2: Play In-App Update (Android only)

`AppUpdateGate` is called from `Activity.onResume()`. It checks `RemoteConfigPort.observe().updatePriority`:

| Priority | Behavior |
|---|---|
| `null` | No update offered |
| `< 4` | Flexible update offered after 7-day cooldown |
| `≥ 4` | Flexible update offered immediately |

Uses `AppUpdateManager.startUpdateFlow` with `AppUpdateType.FLEXIBLE`. No immediate/forced flow — flexible is sufficient combined with the hard gate.

Cooldown tracking uses `SharedPreferences` (`AppUpdatePrefs`) — deliberately lightweight (2 fields, no Flow, no DataStore).

### Layer 3: Remote Config Flags

`RemoteConfigSnapshot.modelFlags` and `mcpToolFlags` drive kill switches at runtime:
- `KoogAgentService` filters `Tool` registration based on `mcpToolFlags`
- Model selection falls back to GPT-4o Mini when a model is disabled via `modelFlags`

### Layer 4: Server-Driven UI (GenUI)

`WhatsNewScreen` renders `RemoteConfigSnapshot.whatsNewPayload` (JSON-Lines A2UI) via the GenUI engine. Shown as a bottom-sheet overlay after `AppVersionGate` passes.

## Architecture

```
RemoteConfigPort.observe()
  └── RemoteConfigSnapshot
        ├── minSupportedVersion   → AppVersionGateScreen (hard block)
        ├── updatePriority        → AppUpdateGate (Android flexible update)
        ├── whatsNewPayload       → WhatsNewScreen (GenUI overlay)
        ├── modelFlags           → KoogAgentService (kill switches)
        └── mcpToolFlags         → ToolRegistry (kill switches)
```

## Rationale

**Why `market://` URI for Android update instead of HTTPS?** `market://` deep-links directly to the Play Store app on-device, bypassing browser. It is the canonical approach recommended by Google for in-app update prompts.

**Why `SharedPreferences` for cooldown and not DataStore?** `AppUpdatePrefs` needs exactly 2 fields (`lastOfferedAt: Long`, implicit `updatePriority: Int` via the config itself). DataStore's transactional model and Flow API are overkill. SharedPreferences is already a transitive dependency of the Play Core library.

**Why no immediate/forced update flow?** The hard `AppVersionGate` at app startup already guarantees users on unsupported versions cannot proceed. The flexible flow handles semi-current versions — users who are running an older version but haven't been blocked yet.

**Why 7-day cooldown?** Prevents nagging users who dismiss the update prompt. The 4-day priority threshold (used for immediate triggering) is aligned with the `minSupportedVersion` cadence — updates that are high priority should be offered as soon as the app loads.

## Consequences

- Play In-App Updates are Android-only; Desktop uses the `AppVersionGate` hard block plus manual download links
- `RemoteConfigPort` schema version must increment if `updatePriority` or any new field is added — existing clients silently fall back to defaults
- The GenUI `whatsNewPayload` is entirely server-controlled content rendered via LLM; it is **not** validated against a schema beyond `A2uiParser` parsing. Trust comes from the authenticated Supabase session.
- No server-driven static content (Layer 4 from the article) — deferred until a concrete surface exists (e.g., in-app FAQ)

## Links

- `docs/decisions/2026-09-23-genui-server-driven-ui.md` — GenUI / A2UI v0.9
- `docs/decisions/2026-09-22-system-calendar-sync.md` — feature module structure pattern
- [Play Core AppUpdate library](https://developer.android.com/guide/playcore/in-app-updates)
