---
name: debugging-investigation
description: Step-by-step incident diagnosis for production issues — log retrieval, trace filtering, crash analysis, common patterns.
---

# Debugging Investigation

## When to use

- A production incident is reported (crash, sync failure, data loss, UI freeze)
- An AI agent is asked to investigate a failing feature
- You need to reproduce a reported issue

## Step-by-step

### Step 1 — Gather context

Ask or determine:
1. Which profile was affected (Profile A, Profile B, all profiles)
2. When did the issue first appear (timestamp or version)
3. Is the issue reproducible (yes/no/sometimes)
4. What is the expected behavior vs actual behavior

### Step 2 — Retrieve relevant logs

```bash
# Android: pull logs from device
adb shell "logcat -d -t 1000" | grep "traceId=XXXX" > /tmp/incident-log.txt

# Desktop: find log file
ls ~/.local/share/singularity/logs/
cat ~/.local/share/singularity/logs/singularity.log | grep "traceId=XXXX"
```

### Step 3 — Filter by trace ID

Every operation chain has a `traceId`. Use it to find all related log entries:

```bash
grep "traceId=abc123" /tmp/incident-log.txt | sort
```

### Step 4 — Identify the failure point

Look for:
- `ERROR` level entries — these are the actual failures
- `WARN` entries before the error — these are the root cause or contributing factors
- State dumps at ERROR — often show the variable values that caused the failure

### Step 5 — Check crash reporting

For crashes, check Firebase Crashlytics:
1. Find the crash group for the affected version
2. Note the exception type and stack trace
3. Check if the same crash has occurred before

### Step 6 — Reproduce if possible

If the issue is reproducible:
1. Enable DEBUG logging: Settings → Developer → Log level → DEBUG
2. Reproduce the exact steps
3. Pull logs and filter by traceId

## Common patterns

### Sync failures

```
WARN  [SyncEngine] sync() failed: Conflict detected for TaskId(...)
  → Resolution: check ConflictResolver for the task
  → If data loss: file ADR retro + recovery plan
```

### Crash on startup

```
FATAL [App] onCreate() threw
  → Check: database migration, SecureStorage decryption, profile initialization
  → Fix: adb shell "dumpsys activity a" for stack at crash time
```

### UI freeze / ANR

```
ANR: Input dispatching timed out
  → Check: main thread blocking call (DB read, network, SharedPreferences)
  → Fix: move to background thread, use缓/async
```

### Data loss

```
WARN  [TaskRepository] delete() returned 0 affected rows
  → Check: was the task actually in the DB? Was profile isolation violated?
  → Fix: restore from backup if available
```

## Decision tree: what to do with each finding

```
LOG SHOWS ERROR
  │
  ├─── Is it a sync conflict?
  │         YES → Check ConflictResolver, file incident report
  │         NO  → Continue
  │
  ├─── Is it a crash?
  │         YES → File Crashlytics issue, check stack trace
  │         NO  → Continue
  │
  ├─── Is it a data loss?
  │         YES → Check backup restoration, file retro ADR
  │         NO  → Continue
  │
  └─── Unknown → escalate to senior engineer
```

## Common pitfalls

1. **Ignoring WARN before ERROR** — the root cause often appears as a WARN before the ERROR
2. **Filtering by timestamp instead of traceId** — timestamp-based filtering misses concurrent operations
3. **Not checking profile isolation** — many "data loss" reports are actually profile isolation working correctly
4. **Reproducing in DEBUG vs release** — some issues only appear in release (ProGuard, stripped logs)

## Prerequisites

- Log access (ADB for Android, log file for Desktop)
- Crashlytics access for crash reports
- Profile ID of the affected user
- Version number of the affected release
