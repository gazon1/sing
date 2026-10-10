---
title: "Gradle Test Cache Silently Skips The Suite"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Found in:** 2026-10-04, immediately after A1 changed the CI test tag filter. The
verification run reported `> Task :desktopApp:test FROM-CACHE` and
`BUILD SUCCESSFUL` — with no test having executed.

**Status: CLOSED — fixed 2026-10-10 by adding `--no-build-cache` to CI Gradle invocations (ci.yml, line 131). The `--gradle-log build.log` is now also passed to `check-test-runs.py` so it can exempt legitimately-cached tasks from the freshness check without suppressing the gate for real runs.**

**Closed via:** `fix/gh-issues-458-374-162` (PR: wire --gradle-log into CI, add --no-build-cache to test invocations)

**Symptom:** a test task whose inputs are unchanged is served from the build cache
and prints success. After editing configuration (test tags, system properties,
harness code paths) the local result can therefore be a cache hit from a run that
predates the edit. `:shared:jvmTest` and `:desktopApp:test` are both configured with
`forkEvery = 1` and parallel execution, which makes them expensive enough that they
stay cacheable for long stretches.

**Already ruled out:** not a no-op task — the XML reports in
`shared/build/test-results/jvmTest/` were regenerated on a forced run and matched
the expected class count (173 shared classes, 27 desktop classes).

**Try next:** any local run that is meant to *verify a configuration change* needs

```bash
./gradlew :desktopApp:test --rerun-tasks
```

A normal run is fine for "did I break the code". It is not fine for "does the new
configuration select the tests I think it selects" — which is exactly the question
A1 had to answer, and the reason the CI job drops `--rerun-tasks` (CI starts from a
cold cache anyway, so this costs nothing there).

The same trap bit `:shared:detektBaseline` three separate ways; see
`detektbaseline-caches-its-output-and-cannot-drain`.


---
