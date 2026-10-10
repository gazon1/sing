---
title: "No Direct Clock System Rule Misses System Currenttimemillis"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #477

**Found in:** 2026-10-07, background agent investigation of `NoDirectClockSystemRule`.

**Symptom:** `NoDirectClockSystemRule` bans `Clock.System` (receiver and call) but does NOT ban `System.currentTimeMillis()`. There are 14 live uses of the latter in `commonMain` production code:

| File | Lines | Purpose |
|---|---|---|
| `core/settings/SettingsDataStoreMigration.kt` | 131, 176 | Migration timestamp markers |
| `feature/ai/data/AiSettingsStore.kt` | 100, 116 | Latency measurement |
| `feature/backup/BackupScreen.kt` | 457, 468 | Hardcoded yesterday epoch for sample data |
| `feature/calendar_sync/auth/GoogleCredentialStore.kt` | 142 | OAuth expiry computation |
| `feature/calendar_sync/domain/logic/CalendarEventMapper.kt` | 104 | Instant from epoch millis |
| `feature/calendar_sync/sync/DirtyHashProvider.kt` | 19, 47 | Hash-slot computation |
| `feature/tasks/domain/util/ReminderFormatter.kt` | 27 | Default parameter for `nowEpochMs` |

The two patterns serve different purposes: `Clock.System` is for wall-clock time (injectable), while `System.currentTimeMillis()` is for epoch timestamps, OAuth expiry, and hash computation — specific use cases that may or may not warrant exemptions per se.

The 6 existing file-level suppressions for `NoDirectClockSystem` are all justified (preview fixtures, ambient logger timestamps, dead code removal) and are unrelated to this gap.

---
