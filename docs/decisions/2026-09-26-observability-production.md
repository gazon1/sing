---
status: accepted
date: 2026-09-26
---

# Observability in Production

## Context

The project lacks observability tooling for production incidents. When something goes wrong, engineers have no structured way to diagnose issues — they rely on tribal knowledge and manual log inspection.

## Decision

### Log levels (Kermit-based)

| Level | When to use |
|---|---|
| **ERROR** | Unrecoverable failures; always written to crash reporting |
| **WARN** | Recoverable failures; degraded but functional |
| **INFO** | Significant business events (task created, sync started, profile switched) |
| **DEBUG** | Verbose diagnostic info (function entry, state transitions) |

**Rule:** Never log user input (task content, note text) at INFO/DEBUG. Log at WARN or ERROR if it triggers an error.

### Structured logging fields

Every log entry from production must include:
- `profileId` — which profile triggered the log (not user identity, just the profile)
- `timestamp` — HLC-based, not wall-clock
- `component` — which feature module generated the log
- `operation` — what operation was being performed

### Metrics to track

| Metric | How | Alert threshold |
|---|---|---|
| Sync duration | Kermit + custom timer around `SyncEngine.sync()` | > 30s |
| DB write latency | Room query timer | > 500ms |
| AI tool call latency | `AiApiCallRecorder` + `LlmUsageEntity` | > 10s per call |
| Crash rate | Firebase Crashlytics | > 0.1% per release |
| ANR rate | Android Vitals | > 0.1% |

### Tracing

- Each sync operation gets a trace ID via `HlcTimestamp`
- Cross-feature operations (e.g., task create → notification) link via `parentTraceId`
- Logcat filtering: `grep "traceId=XXXX"` shows the full operation chain

### What NOT to log

- User content (task text, note text, tags)
- API keys, tokens, or credentials
- Profile data beyond ID
- Any PII

## Consequences

- `debugging-investigation` skill gives engineers a step-by-step diagnosis procedure
- Log filtering by trace ID is the primary incident investigation tool
- Crash reporting via Crashlytics is the primary stability metric
