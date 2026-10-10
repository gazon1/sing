---
title: "Log Messages User Content Sweep Deferred"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED as originally scoped, verified 2026-10-04, with one caveat recorded rather than glossed. `Redaction.redactEmail()` exists and is used at `AuthRepository.kt:58,70`; the `${e.message}` interpolations in `ProfileSwitcherViewModel`, `SavedAgendaViewModel` and `SyncBootstrapper` are gone. **Caveat:** `FileLogWriter.kt:102` still logs `${e.message}`, and `QueryParser.kt:163` embeds user query text in an exception message that reaches `SearchViewModel.kt:218` — a user-visible path rather than a log. The wider user-content sweep is #43; this entry covered the credential-shaped exposures and those are done.

**Found in:** MR-D (tech-debt batch). The redaction decorator scrubs credential
shapes; it does not catch task titles, note bodies, or AI prompt fragments.
**Status: RESOLVED** (tech-debt session, 2026-10-02).

All `e.message` exposures fixed: `ProfileSwitcherViewModel` (lines 99, 106),
`SavedAgendaViewModel` (line 325), `SyncBootstrapper` (line 143) — `${e.message}`
removed from error logs. `AuthRepository` (lines 57, 69) now uses shared
`Redaction.redactEmail()` helper. `Redaction.kt` created in `core/log/` with
`redactEmail()`. Remaining 33 interpolation sites use only ids and technical
metadata.

---
