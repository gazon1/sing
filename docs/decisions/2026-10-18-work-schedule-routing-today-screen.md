---
title: "Work Schedule Routing — Wire point for Today screen"
date: "2026-10-18"
status: "accepted"
type: "decision"
---

## Context

The WorkSchedule settings (dayStartMinutes, dayEndMinutes, lunchStartMinutes, lunchEndMinutes) are persisted in a DataStore. The "Today" agenda screen should render with work-block sections derived from these settings, but the wire between the DataStore and the composable was missing.

## Decision

Wire `WorkScheduleSettingsRepository` into `AgendaRouteMapping.kt` for the `Today` route using `koinInject()`. Read the four flow values using `collectAsStateWithLifecycle()` with a `LaunchedEffect` that computes `AgendaPresets.workDayPreset()` once all four values are loaded. Fall back to the static `AgendaPresets.Today` preset if any read fails (e.g. in preview contexts).

## Rationale

`AgendaRouteMapping.kt` is the composable that runs once per navigation and owns the route→screen mapping. It already uses `koinInject()` for other dependencies, making it the natural place to pull work-schedule settings without changing the VM's constructor contract. Using `collectAsStateWithLifecycle()` keeps the values reactive — if the user changes work hours in Settings, the composable recomposes automatically. The fallback to the static preset ensures the app never crashes due to a missing settings value.

## Consequences

- Positive: Work-block sections render on the Today screen using real user settings
- Positive: No VM constructor change; wiring stays in the Compose layer
- Positive: Settings changes propagate without restarting the VM
- Negative: Brief flicker (one frame) between navigation and preset computation

## Links

[]
