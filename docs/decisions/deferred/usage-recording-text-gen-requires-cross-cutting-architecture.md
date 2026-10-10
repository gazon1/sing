---
title: "Usage Recording Text Gen Requires Cross Cutting Architecture"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "usage-recording-platform-parity"]
---

**Tracked as:** #106
**OpenSpec change:** `openspec/changes/usage-recording-platform-parity/`

**Status: CLOSED** (Android LLM usage recording fixed separately; issue #106 closed). and the claim was only true of the JVM. `AiToolsModule.jvm.kt:105-118`
wraps `TextGenPort` in `UsageRecordingTextGen`; `AiToolsModule.android.kt:102`
still binds a raw `KoogAgentService` with no decorator, so no Android LLM call is
ever recorded. Each platform's build is green, which is why it went unnoticed —
the feature looks alive on the development platform and is absent on the one
users run. Tracked as #106.

ADR `2026-10-02-usage-recording-textgen-architecture.md` defines the pattern:
decorator lives in `feature/ai/chat/`, receives `RoomUsageRecorder` and
`ProfileAwareCurrentUser` via Koin DI (feature→core dependency allowed).
`AiToolsModule.jvm.kt` binds `Clock.System` locally; the decorator replaces
the raw `KoogAgentService` binding. `UsageRecordingTextGen` now records
every `TextGenPort.generate()` and `streamChat()` call to `RoomUsageRecorder`.
