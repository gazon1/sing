---
title: "NotificationChannel enableVibration → setVibrationPattern"
date: "2026-10-18"
status: "accepted"
type: "decision"
---

## Context

On Android API 26+, `NotificationChannel.enableVibration(Boolean)` is deprecated and has no effect. Vibrations must be set via `setVibrationPattern(LongArray?)`.

## Decision

Replace `enableVibration(vibration: Boolean)` with `setVibrationPattern(if (vibration) longArrayOf(0, 250, 250, 250) else null)` in `AndroidNotifier.kt`. The pattern `[0, 250, 250, 250]` produces a short double-beep vibration consistent with a standard notification reminder. When `vibration=false`, pass `null` to disable vibration entirely.

## Rationale

`enableVibration()` silently does nothing on Android O+ (API 26+). Users who disabled vibration in settings would still feel their device vibrate because the channel was configured with `enableVibration(true)` by default. The `setVibrationPattern()` approach is the correct API for API 26+ and is also compatible with older versions through the `NotificationCompat` builder path.

## Consequences

- Positive: Vibration setting now works correctly on modern Android
- Positive: Pattern `[0, 250, 250, 250]` is short and non-intrusive
- Negative: None

## Links

[]
