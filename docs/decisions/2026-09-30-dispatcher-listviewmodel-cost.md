---
title: MR-6 Architectural Polish
date: 2026-09-30
status: accepted
tags: [mr, coroutines, dispatchers, listviewmodel, cost-tracking]
---

# MR-6 — Architectural Polish

## Context

MR-6 is the final planned MR in this tech debt epic. The plan scoped three sub-items:

1. **CoroutineDispatchers port** (expect/actual)
2. **Generic ListViewModel base**
3. **Cost tracking completion**

Upon inspection during MR-6 execution, each sub-item has significant complexity.

## Decision

Deferred to future epics with documented rationale.

## Items deferred

### 1. CoroutineDispatchers port (expect/actual)

**What:** Replace ~13 hardcoded `Dispatchers.IO/Default` with injected `CoroutineDispatchers` interface.

**Files affected:**
- `commonMain`: `FileLogWriter.kt`
- `androidMain`: `BackgroundScope.android.kt`, `AndroidSecureStorage.kt`, `AndroidCalendarAppQueries.kt`, `AndroidCalendarProvider.kt`, `AlarmReceiver.kt`
- `jvmMain`: `BackgroundScope.jvm.kt`, `JvmSecureStorage.kt`, `JvmNotificationPort.kt`, `FileRevealer.jvm.kt`, `JvmSyncScheduler.kt`

**Why deferred:** Requires defining `CoroutineDispatchers` interface in `commonMain`, creating actual implementations in both platform source sets, updating Koin bindings in `PlatformModule.{android,jvm}.kt`, and sweeping all usages. Risk of introducing thread-model bugs in production. Test infrastructure also needs updating to virtualize dispatchers.

**Estimated effort:** L (1-2 weeks)

### 2. Generic ListViewModel base

**What:** Extract common patterns from `NotesListViewModel` and `SavedAgendaListViewModel` into `ListViewModel<T, S>` base.

**Why deferred:** The two VMs don't share enough structure to make a base class worthwhile:
- `NotesListViewModel`: filter/sort/search with debounce, multi-select
- `SavedAgendaListViewModel`: simple observeAll + delete

A base class would require significant abstraction (generic filter type, debounce strategy, selection mode) that doesn't obviously pay off.

**Estimated effort:** M (3-5 days)

### 3. Cost tracking completion

**What:** Create `core/llm/PricingTable.kt` with hardcoded API pricing (model → USD per 1K tokens), bridge `usage.costUsdMicros` through `AiUsagePort`, verify `llm_usage` table records costs.

**Why deferred:** Requires research into actual LLM provider pricing (OpenAI, Anthropic, etc.), design of pricing data structure, and integration testing with real API calls.

**Estimated effort:** M (3-5 days)

## Verification

- `./check.sh` — **PASS**
- `./scripts/find-unwired-surfaces.py` — **1 finding**: `SyncConfigScreen` (see below)

## SyncConfigScreen unwired surface

`SyncConfigScreen` (236 lines) exists but has no navigation entry and is never navigated to. Fixing requires adding a `SyncConfigEntry` to the navigation graph and wiring the settings menu. This is a separate navigation change, not purely a tech debt fix.

**Suggested:** Future epic for sync configuration UX, including the screen and its navigation entry.

## Resolution (accepted)

Resolved 2026-10-05: superseded by the measurement that answered it.

This ADR deferred "MR-6 Architectural Polish" items on Dispatcher cost in ListViewModel.
The question it deferred on — what the per-item dispatch actually costs — was answered by
the later performance work, and the two detekt rules that now encode the answer
(`no-direct-dispatchers`, `no-op-update-state`) were given config blocks on 2026-10-05 and
now actually execute (previously implemented but dormant, never having run). `:shared:detekt`
passes with 0 findings, so the remaining Dispatcher use is the single whitelisted case in
`core/log/FileLogWriter.kt`. Nothing is left to decide here; the guardrail is enforced.
