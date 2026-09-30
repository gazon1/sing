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

## Writing it up: incident report / retro ADR

Once you have the root cause, write it down — an investigation that lives only in the
terminal is gone by the next session. The decision tree above says "file incident report"
and "file retro ADR"; this is what that looks like.

**Investigation in progress** (cause known, fix not yet landed) — an incident report:

```markdown
---
date: YYYY-MM-DD
status: open
tags: [incident, <area>]
---

# <Symptom as the user saw it>

## Timeline
- <when it started, and what preceded it — deploy, config change, data migration>
- <what was already ruled out>

## Root cause
<The mechanism, not the symptom. "The debounced write loop re-triggered on its own
emission, so the title field never settled" — not "the title was wrong".>

Evidence: <log excerpt, traceId, failing test, or the query that showed it.>

## Blast radius
<Which profiles, which data, whether it self-heals, whether a backup is needed.>

## Fix or workaround
- Fix: <what actually changes, and where>
- Workaround: <what unblocks users now, if the fix is not ready>
```

**After the fix** — flip `status: open` to `accepted` and add `## Prevention`: what now
stops this class of bug. Usually one of:

- a test that fails without the fix (the strongest kind)
- a detekt rule or Konsist test (`singularity-todo-detekt-rules-authoring`)
- a pattern note in the relevant skill, so the next agent does not rediscover it
- nothing — a genuine one-off, in which case say so rather than inventing prevention

**Worth keeping:** "why did this pass review and tests" is a more useful question than
"how did we fix it", because it points at the gap rather than the symptom. Write that
answer down while it is fresh.

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

---

# Debugging a failing test

The section above assumes a production incident. This one is for "the test is red
and I do not know why" — where there are no logs, because the failure is inside
the test JVM.

## The loop that works

1. **Read the assertion message before the source.** Compose failures are precise
   once you have the node list: "found '2' nodes that satisfy…" means an
   ambiguity, "could not find any node" means the selector is wrong, and a
   timeout means the value never arrived. These need three different responses.
2. **Dump the tree; never guess a selector.** Guessed selectors are the single
   largest source of wasted turns. Every desktop flow honours
   `-Dsingularity.ui.dumpTree=true`, and the semantics tree lands in
   `build/test-results/test/TEST-*.xml` between `=== SEMANTICS TREE ===` markers.
   Anything you "know" is on screen from reading source is a guess.
3. **Turn on verbose logging.** `-Dsingularity.test.log=true` routes Kermit to
   stdout at `Verbose`. Kermit's default already writes, but the default
   severity hides `Logger.d`/`Logger.v`, which is where repository and ViewModel
   tracing lives.
4. **Bisect across the layer boundary.** When the UI disagrees with the data,
   resolve the repository directly from the harness's `Koin` and ask it what it
   holds. "The repository returns the task and the screen shows none" is a
   completely different investigation from "the repository returns nothing", and
   guessing between them wastes the most time.
5. **Distinguish "did not happen" from "did not render".** A missing node is
   ambiguous between the two. Check the data layer before touching selectors.

## Traps

- **`-D` on the Gradle CLI configures the daemon, not the test JVM.** Opt-in test
  switches need explicit forwarding in the test task config
  (`desktopApp/build.gradle.kts` forwards `singularity.*`). Without it the flag
  parses cleanly and does nothing, which reads as "the switch is broken".
- **A test that passes alone and fails in the suite is shared state, not a bad
  selector.** Check for process-global mutation before re-reading the test.
- **Koin duplicate definitions resolve last-wins.** If a fake in a test module
  seems ignored, the production module was probably loaded *after* it.
- **"Repository is empty" can mean the write was scoped to a different user.**
  `ProfileAwareCurrentUser.scopedUserId` is not stable at startup — check what it
  is at write time and at read time before concluding anything.
- **A disabled button makes `performClick` a silent no-op.** If a click "does
  nothing", assert the enabled state before blaming the handler.
- **A `try`/`finally` with no `catch` swallows exceptions.** A control the user
  pressed that quietly does nothing usually means a throw escaped into the
  coroutine scope. Look for a `catch` first, at the call site that can throw.
