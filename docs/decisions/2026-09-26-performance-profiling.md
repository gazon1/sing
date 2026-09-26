---
status: accepted
date: 2026-09-26
---

# Performance Profiling

## Context

The app has performance issues that are hard to diagnose without a structured profiling approach. This ADR defines the tools and process for identifying and fixing performance regressions.

## Decision

### Profiling tools

| Platform | Tool | What to profile |
|---|---|---|
| Android | Android Studio Profiler | CPU time, memory allocations, network |
| Android | systrace / Perfetto | Long UI frames (jank), startup time |
| Android | Jetpack Benchmark | Critical path microbenchmarks |
| Desktop JVM | IDEA Profiler | CPU sampling, memory heap |
| Desktop JVM | async-profiler | Low-overhead CPU profiling |

### What to measure

1. **Startup time** — cold start < 2s on mid-range device
2. **UI frame time** — 90% of frames < 16ms (60fps)
3. **DB query time** — 90% of queries < 50ms
4. **Sync duration** — median sync < 5s for 1000 entities
5. **Memory** — P95 heap < 200MB

### Profiling workflow

1. **Identify** — use Android Studio Profiler or systrace to find the hot path
2. **Isolate** — write a microbenchmark for the hot path (Jetpack Benchmark for Android)
3. **Optimize** — apply the fix
4. **Verify** — benchmark confirms improvement, no regression in other areas
5. **Document** — record the fix and the benchmark result in the ADR

### Common bottlenecks

| Bottleneck | Symptoms | Fix |
|---|---|---|
| Main thread blocking | UI jank, ANR | Move to background thread |
| Lazy Column without keys | Recomposition storm | Add `key` to items |
| N+1 queries | Slow DB operations | Batch query or join |
| Unnecessary state copies | Memory spike | Use `snapshotFlow` or `derivedStateOf` |
| Large composable recomposition | Slow frame render | Break into smaller composables |

### Startup profiling

For cold start issues:
1. Use `adb shell am start -W -n` to measure launch time
2. Use systrace to see which init step is slowest
3. Common culprits: first sync, DB migration, SecureStorage decryption

## Consequences

- Performance issues should be measured before being fixed — no "feel" optimizations
- `ux-a11y-review` skill covers non-functional requirements beyond performance
