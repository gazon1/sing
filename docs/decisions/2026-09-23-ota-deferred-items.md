---
status: accepted
---

# OTA Deferred Items — post-MR follow-ups

## Context

After completing MR-1 through MR-8 (Play In-App Updates, Remote Config flags, GenUI WhatsNew surface), several issues remain unresolved. They are documented here so they aren't lost.

## Deferred items

### 1. Multi-store support (RuStore, Samsung Galaxy Store)

The current `AppUpdateGate` uses Play Core's `AppUpdateManager` exclusively. It only works for apps distributed via Google Play.

**Why deferred**: no current RuStore distribution. Adding support now would introduce unused complexity.

**When to revisit**: when the app is published to RuStore (estimated Q1 2027 per roadmap).

**Proposed approach**: introduce an `UpdateStorePort` interface with three implementations:

```kotlin
interface UpdateStorePort {
    fun tryOfferUpdate(activity: Activity): Boolean
}

class GooglePlayUpdateStore(...) : UpdateStorePort
class RuStoreUpdateStore(...) : UpdateStorePort     // rustore SDK
class DirectUrlUpdateStore(url: String) : UpdateStorePort  // opens browser
```

The active implementation is selected via `RemoteConfigSnapshot.updateStoreType`. `RemoteConfigSnapshot.updateStoreUrl` overrides the destination URL for non-Play stores.

For MVP (no SDK integration), `updateStoreUrl` alone is enough — open it in a browser tab via `Intent.ACTION_VIEW` with the same priority/cooldown logic already in `AppUpdateGate`.

### 2. `AppUpdateGate.tryOfferUpdate` is main-thread-only

`tryOfferUpdate` calls `Handler.post {}` internally and assumes the caller is on the main thread. If invoked from a background coroutine, it silently posts to main (correct behavior), but the `Handler` field initialization happens lazily — which is fine but undocumented.

**Why deferred**: the only call site is `MainActivity.onResume()`, which is always on the main thread.

**Proposed fix**: rename to `tryOfferUpdateOnMain` to make the constraint explicit, or accept any thread and use `Dispatchers.Main`.

### 3. `WhatsNewScreen` blocks on first composition

The first `LaunchedEffect` reads DataStore via `prefs.shouldShow()` — this is suspending, which Compose handles correctly via `LaunchedEffect`. But the initial render of the sheet happens *before* the `shouldShow` check resolves, so users may see a flash of an empty sheet if DataStore I/O is slow.

**Why deferred**: DataStore is fast on Android (~5ms for a single key read).

**Proposed fix**: render a `CircularProgressIndicator` placeholder until `surfaceId != null`, gated by a separate `var isLoading by remember { mutableStateOf(true) }` flag.

### 4. `WhatsNewScreen` does not handle re-show on payload change

Currently, if the server sends a new `whatsNewPayload` while the user has the app open and the sheet is dismissed, the new payload will be shown only when the user navigates away and back. The `LaunchedEffect` re-runs on `snapshot.whatsNewPayload` change, so the sheet will re-open — but only if the sheet was previously dismissed (via `prefs.markShown`). For the first time, this is correct.

**Why deferred**: this is the expected behavior — release notes are surfaced once per release.

### 5. `WhatsNewPrefs` does not expire

A user who dismisses the WhatsNew screen will never see the same payload again, even if a year passes. If the server reuses an old payload string (e.g., test fixture), the sheet stays hidden.

**Why deferred**: server-controlled content is expected to be unique per release.

**Proposed fix**: add a TTL — store `lastShownAtEpoch: Long` alongside `lastShownHash`; re-show after 90 days even if hash matches.

### 6. Pre-existing detekt baseline issues

`WhatsNewScreen`, `ChatScreen`, `AiUsageScreen`, `CalendarScreen` and ~20 other `*Screen` composables violate `FunctionNaming` (PascalCase). The whole codebase has this pattern; it's accepted as a pre-existing baseline.

**Why deferred**: out of scope for OTA strategy. Refactor would touch 20+ files.

**Proposed fix**: rename `WhatsNewScreen` to `WhatsNewSheet` (matching actual behavior — it IS a sheet, not a full screen) as a one-off exception, or relax `FunctionNaming` rule in `detekt.yml` to allow `*Screen` composables via `@Suppress` annotations.

### 7. ADR for SupabaseEndpointConfig rename

`core/sync/RemoteConfig.kt` was originally slated for renaming to `core/sync/SupabaseEndpointConfig.kt` to disambiguate from `core/config/RemoteConfigPort.kt`. The MR was deferred because the rename touches 4 files (entity, DAO, repo, DI) and the names are not actively confused today.

**Why deferred**: no current ambiguity.

**Proposed fix**: 1-hour rename MR.

## Consequences

- The four layers of the OTA strategy (gate, in-app update, flags, GenUI) are production-ready for Google Play distribution.
- RuStore / Galaxy Store support requires ~1 day of work when distribution to those stores is planned.
- Minor UX polish (loading placeholder, TTL) can be added opportunistically when the screen is touched.

## Links

- `docs/decisions/2026-09-23-ota-update-strategy.md` — high-level OTA strategy
- `docs/decisions/2026-09-23-genui-server-driven-ui.md` — GenUI / WhatsNew
