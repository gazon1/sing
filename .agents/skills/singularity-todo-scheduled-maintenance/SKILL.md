---
name: singularity-todo-scheduled-maintenance
description: Runtime measurement and periodic health checks for the Singularity Todo desktop (JVM) app. Use when memory grows over a session, when startup feels slow, when a change might have made the UI janky, or as a periodic check before a release. Wraps scripts/ram-bench.sh and says what the numbers mean.
---

# Scheduled maintenance (runtime)

The doc-audit counterpart: measuring the running app rather than the files. Two numbers in
this project are easy to regress without any test failing — resident memory over a long
session, and startup cost as the DI graph grows.

## When to run

| Trigger | Why |
|---|---|
| After a change to the DI graph, the database layer, or the AI tool set | those are the paths that allocate the most and are covered by no test |
| Before a release | a regression found in CI is a rollback; found here it is a note |
| When someone reports the app "feels heavy" or grows over a session | usually a retained scope or a listener that never detached |
| When startup gets noticeably slower | Koin module count and Room migration cost both show up here |

## Memory

```bash
# Launch the desktop app first, then:
./gradlew :desktopApp:run &          # or the already-running process
./scripts/ram-bench.sh               # finds the pid via pgrep
./scripts/ram-bench.sh <pid>         # explicit pid when there are several
```

Four numbers matter, and they mean different things:

| Metric | Reading it |
|---|---|
| `VmRSS` | physical RAM right now — the headline number |
| `VmPeak` | worst case since launch; a high peak with a low RSS means a burst (e.g. a large backup import), a leak looks different: RSS climbs and stays |
| `VmSwap` | anything non-zero on a desktop machine with RAM to spare is worth understanding |
| `jcmd GC.heap_info` used vs. max | distinguishes *live* data from garbage the collector has not reclaimed. Rising `used` with a flat `VmRSS` is usually just GC lag; both rising together is retention |
| Threads | a thread count that grows per screen visit means something is registering a listener per entry |

**Comparing two runs means comparing like with like.** Same build, same dataset, same
sequence of screens, same wait after each step — the script's header suggests cold start
(5s), 30s idle, then the AI chat screen, then a 100-task stress. A number is only a
regression relative to a recorded baseline; keep the output of the last known-good run
next to the ADR that changed the code.

**What actually causes growth here, in order of likelihood:** a `CoroutineScope` created
per screen instead of per VM (`singularity-todo-coroutine-scopes`), a `StateFlow` kept alive
by a `stateIn` in production (`singularity-todo-testable-vm` bans it, and a detekt rule
enforces it), and Room observers that are never cancelled.

## Startup

```bash
time ./gradlew :desktopApp:run
```

Gradle overhead dominates, so measure the app's own start, not the wrapper: `VmPeak`
during the first seconds, and how long until the first frame is on screen. Startup cost
scales with Koin module count and Room migration version, so a new `*DiModule` is not free
— though neither is it usually the problem.

## When a number is bad

1. Re-measure to rule out noise before believing it.
2. Bisect by build, not by reading code — a bisected regression is a fact.
3. File an incident report (`debugging-investigation`) with the before/after numbers
   attached. Numbers in the report stop the next person re-deriving them.
4. If the fix is structural, prefer a rule over a note: a detekt rule or a Konsist test
   costs nothing to keep and cannot be forgotten.

## Related

- `singularity-todo-monthly-doc-audit` — the files-side counterpart
- `singularity-todo-coroutine-scopes` — the usual cause of retained memory
- `singularity-todo-quality-tools` — kover coverage, for the parts a benchmark cannot see
