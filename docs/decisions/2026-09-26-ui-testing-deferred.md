---
title: "UI testing deferred — androidHostTest + UiAutomator postponed"
status: accepted
date: 2026-09-26
---

# UI Testing Deferred

## Context

Four ADRs from September 2025 established an intent to build UI testing infrastructure:

- `2026-09-05-robolectric-widget-tests` — Widget tests via Robolectric `androidHostTest`, no Koin, direct ViewModel construction
- `2026-09-05-uiautomator-compose-discovery` — UiAutomator-based Compose element discovery
- `2026-09-05-ui-tests-ultron` — Ultron testing strategy with minimal DI seams
- `2026-09-06-desktop-smoke-test-with-koin` — Desktop smoke test with Koin

These were **accepted but not implemented**. No Robolectric widget tests, no UiAutomator suites, and no Ultron integration exist in the codebase today.

## Decision

1. **Mark the four ADRs as superseded** — they remain in `docs/decisions/` for archaeology but are superseded by this record.
2. **UI testing deferred to a future epic.** The current priority is documentation hygiene and architecture cleanup. UI testing infrastructure requires dedicated investment beyond the scope of this epic.
3. **The testing stack remains:**
   - `commonTest` + `jvmTest` for pure Kotlin logic (fast, deterministic)
   - `@Tag("slow")` exclusion for flaky/slow tests (see `test-suite-tag-defaults`)
   - FakeRepositories for VM testing (see `test-helpers`)
   - No Robolectric, no UiAutomator, no Ultron in CI

## Rationale

The four original ADRs described an ambitious testing stack that was never built. Keeping them as `accepted` implied work-in-progress that doesn't exist. Marking them `superseded` closes the loop honestly.

## Consequences

- No immediate change to test infrastructure or CI.
- When a future epic resumes UI testing, the original ADRs serve as context for what was considered.
- `androidHostTest` configuration (Robolectric) exists in `shared/build.gradle.kts` but runs 0 tests — no harm in leaving it.

## Superseded ADRs

- `2026-09-05-robolectric-widget-tests`
- `2026-09-05-uiautomator-compose-discovery`
- `2026-09-05-ui-tests-ultron`
- `2026-09-06-desktop-smoke-test-with-koin`
