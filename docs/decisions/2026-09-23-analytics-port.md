---
title: "Analytics port: interface + Noop + GDPR-compliant opt-in default"
date: 2026-09-23
tags: [analytics, observability, gdpr]
status: accepted
---

## Context

Singularity Todo has no general-purpose analytics layer. `UsageRecorder` tracks LLM token consumption per profile, but there is no way to track user-facing events (task created, screen opened, sync completed) for product analytics. The Tasks.org app has a clean, minimal `Analytics` interface with `logEvent`, `identify`, and `logEventOncePerDay` — all implemented as no-ops for now, ready to swap in a real SDK (Amplitude, Mixpanel, PostHog) later.

EU GDPR requires opt-in consent for analytics. Default must be off.

## Idea

1. Define `Analytics` port interface in `core/analytics/Analytics.kt` with `logEvent`, `identify`, and `logEventOncePerDay` (daily deduplication via DataStore).
2. Provide `NoopAnalytics` as the default Koin binding — all methods are no-ops, zero overhead.
3. Add `analyticsOptIn: Flow<Boolean>` to `SettingsRepository` with default `false`.
4. Document the event name constants in `AnalyticsEvents` object — same naming convention as Tasks.org (screen names, action names, `PARAM_*` prefixes).
5. `ProfileAwareAnalytics` decorator is intentionally **not added** — will be added when a real SDK is connected and requirements are clearer.

## Decision

### Interface

```kotlin
interface Analytics {
    fun logEvent(event: String, vararg params: Pair<String, Any>)
    fun identify(distinctId: String)
}

suspend fun Analytics.logEventOncePerDay(
    preferences: DataStore<Preferences>,
    key: String,
    todayEpochDay: Long,
    vararg params: Pair<String, Any>,
) { /* writes last-logged day to DataStore; skips if same day */ }
```

`NoopAnalytics` implements all methods as no-ops.

### GDPR compliance

`analyticsOptIn` in `SettingsRepository` defaults to `false`. The Settings UI will expose a toggle in a future PR. Until a real SDK is connected, `NoopAnalytics` is always bound regardless of the toggle value — the toggle is a forward-compatibility placeholder.

### Event naming

`AnalyticsEvents` object mirrors Tasks.org conventions:
- Screen events: `SCREEN_ADD_ACCOUNT`, `SCREEN_PRICING`, `SCREEN_WELCOME`
- Action events: `ADD_TASK`, `COMPLETE_TASK`, `SIGN_IN_ERROR`
- Settings clicks: `SettingsClick.DELETE_LIST`, `SettingsClick.SEND_LOGS`
- Cloud onboarding: `CloudOnboarding.TRIGGERED`, `CloudOnboarding.SIGN_IN`, etc.
- Parameters: `PARAM_SOURCE`, `PARAM_PROVIDER`, `PARAM_TIER`, `PARAM_STEP`

### Directory structure

```
core/analytics/
  Analytics.kt          — interface + logEventOncePerDay extension
  NoopAnalytics.kt     — all-no-op implementation
  AnalyticsEvents.kt    — event name constants
```

## Rationale

- **Interface first**: the real value of this ADR is the port boundary. Adding `Amplitude.init()` later requires changing only the Koin binding — no call sites change.
- **Noop by default**: zero-cost for users who opt out; zero SDK init time on cold start.
- **`logEventOncePerDay` in interface**: Tasks.org's pattern (Firebase Analytics equivalent) prevents spam from repeated daily events. Implemented via a `longPreferencesKey("last_logged_$event:$dedupeBy")` in DataStore.
- **`analyticsOptIn` placeholder**: the toggle is wired but has no effect yet. This is intentional — we add the toggle early so the Settings UI schema is stable, and the real wiring (Amplitude SDK) can be dropped in without another schema migration.

## Consequences

- No real analytics SDK is connected. `logEvent` calls in the codebase are safe no-ops.
- When a real SDK is added: create `RealAnalytics : Analytics` (wrapping Amplitude/Mixpanel/PostHog), change Koin binding from `NoopAnalytics` to `RealAnalytics`, remove this ADR's "no-op" status.
- `ProfileAwareAnalytics` decorator (adding `profile_id` to every event) will be added when the real SDK is connected — at that point we know whether the SDK handles profile identity natively.
- Crash reporting (`CrashReporting` interface from Tasks.org) is intentionally separate — will be addressed in a dedicated ADR when Sentry/Crashlytics is evaluated.

## Links

- `shared/src/commonMain/.../core/analytics/Analytics.kt`
- `shared/src/commonMain/.../core/analytics/NoopAnalytics.kt`
- `shared/src/commonMain/.../core/analytics/AnalyticsEvents.kt`
