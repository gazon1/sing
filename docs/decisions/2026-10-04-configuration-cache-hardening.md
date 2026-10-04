---
title: "Configuration Cache hardening: fail-on-problem, CI diagnostics, and the three bugs the smoke tests found"
date: 2026-10-04
status: accepted
tags: [build, gradle, configuration-cache, ci, testing]
---

# Configuration Cache hardening (issue #29)

## Context

Configuration Cache was already globally on (`gradle.properties`
`org.gradle.configuration-cache=true`) but nothing hardened it:

- The CC store lives in the **project** `.gradle/configuration-cache/`, not
  `GRADLE_USER_HOME` — so `gradle/actions/setup-gradle@v4` (which caches only
  the Gradle User Home) never cached it, and CC problems were only warned about.
- CI invoked Gradle without `--configuration-cache`, never uploaded CC reports,
  and **never built `:mcp-server:jar`** — the fat-jar grey zone
  (`configurations.runtimeClasspath.get()` captured inside `from({...})`) was
  exercised nowhere, and `McpServerEndToEndTest` always self-skipped.
- ADR `2026-10-01-test-infra-gaps` §2 claimed that editing a
  `systemProperty`-reading test left stale compiled classes served by CC, with
  `--rerun-tasks` as the required workaround (also mirrored in AGENTS.md).

## Decision

### A1 — Baseline (measured, `time ./gradlew :shared:jvmTest :desktopApp:test`)

| Run | Result |
|---|---|
| first store | **4m 01s** ("Configuration cache entry stored") |
| immediate rerun | **11s**, then 7–8s ("entry reused"; one run saw a benign one-off invalidation: *input to unknown location has changed*) |
| cold (`rm -rf .gradle/configuration-cache`, tasks up-to-date) | **8s** (store re-created) |

The expected CC failure of `:mcp-server:build` did **not** reproduce: `jar`
runs CC-clean (lazy `runtimeClasspath.get()` inside `from({...})` is a
supported CC pattern — Gradle defers the provider). `build` failed instead on
a **plain, CC-independent** bug: `distZip`/`distTar` rejected duplicate entries
(`savedstate-compose-desktop-1.4.0.jar` etc.) because `runtimeClasspath`
contains the same Compose/Lifecycle/SavedState artifacts under **both**
`androidx.*` and `org.jetbrains.*` coordinates (identical version, identical
file name, identical content).

### A2 — `:mcp-server:build` green under CC

- `tasks.withType<AbstractArchiveTask>().configureEach { duplicatesStrategy = EXCLUDE }`
  (not `Zip` — `distTar` is a `Tar`), mirroring the strategy the fat-jar
  already had. Double run: `stored` → `reused` in 0.6s.
- The smoke criterion (`java -jar mcp-server.jar --profile=ai-agent`) exposed
  the real blocker: `Main.kt` hand-rolled a private `platformModule()` that had
  **drifted from `core/di/PlatformModule.jvm.kt`** — `SyncWorkScheduler`,
  proposal/tag-group/DAO and split-DataStore bindings missing →
  `NoDefinitionFoundException` during `ToolRegistrar.registerAll()` on startup.
  Fixed by importing the shared `platformModule()` (same composition the
  desktop app and `KoinGraphValidationTest` use).

### The upgrade-path bug (found by A2's E2E criterion, fixed here)

`Migration31To32` was `@DeleteColumn(ai_proposal, task_id)` +
`AutoMigrationSpec.onPostMigrate` — the generated migration rebuilt the tables
**first** and backfilled `target_id = task_id` **after** the drop →
`no such column: task_id` → whole upgrade rolled back. room3's
`AutoMigrationSpec` has **no `onPreMigrate`**, so no auto-migration ordering
can fix this (`Migration30To31`'s KDoc already documented the pitfall — and
31→32 walked into it anyway). Impact: every pre-v32 database is permanently
stuck (the dev machine's DB was at v25; `McpServerEndToEndTest` failed on
every machine with a real home DB).

Decision: 31→32 is a **manual `Migration(31, 32)`** registered via
`addMigrations()`; it backfills (and deletes un-representable targetless rows —
`target_id` becomes `NOT NULL`, `ProposalMapper` rejects null targets,
`ai_proposal_item` cascades), then replays the verbatim KSP sequence.
Regression test `Migration31To32Test` replays the exported `31.json` fixture
through real Room (schema validation against `32.json` included).

**Verification on the real user DB (copy):** `user_version 25 → 32`,
`tasks 35 / notes 2 / projects 5 / profiles 2` identical before/after,
`ai_proposal.task_id` gone, server boots and serves `list_tasks`.

### A2 extras required to make the E2E guard real

- `McpServerEndToEndTest` spawns the JAR with an isolated
  `-Duser.home=<temp>` — it previously ran migrations **and wrote test tasks
  into the developer's real `~/.singularity-todo/singularity-todo.db`**.
- It called tools `tasks.create`/`tasks.get` which **never existed**
  (registry is snake_case: `create_task`/`get_task`) — the suite had never
  passed; after the migration fix its `catch` hung the build by calling
  `readText()` on an *alive* child's stderr (EOF never comes). Fixed:
  correct names + `destroyForcibly()` before draining stderr.
- `TOOL_ANNOTATIONS` had the same dotted-vs-snake_case drift: **25/26 keys
  dead**, no tool shipped annotations. Remapped to the 21 real tool names
  (verified over `tools/list`: 21/37 tools now carry annotations).

### A3 — Stale test classes: NOT reproducible (hypothesis disproven)

Two-way experiment on a warm CC store, plain reruns (no `--rerun-tasks`):

1. sabotage `MaestroFlowTagsTest.workspaceRoot` → **recompiled**, failed with
   the exact historical `scanned 0 ids` message;
2. revert → **recompiled** (build-cache hit, correct source) and passed;
3. changing a `systemProperty` value in `shared/build.gradle.kts`
   re-executed `jvmTest` next run (then `UP-TO-DATE` + "entry reused") —
   system properties *are* tracked task inputs.

CC stores the configuration/task graph, not compiled classes; compiled outputs
are governed by normal up-to-date checks. The original symptom was most likely
a misdiagnosis (unsaved edit / wrong worktree); the ADR §2 also misstated the
store location (`~/.gradle/…`). Actions: `2026-10-01-test-infra-gaps` §2 got a
NOT-REPRODUCIBLE banner, the AGENTS.md `--rerun-tasks` workaround was
rewritten to "not a thing (verified 2026-10-04)".

### A4 — CI

- `--configuration-cache` explicit on **every** Gradle invocation
  (`ci.yml`, `maestro-smoke.yml`, `maestro-nightly.yml`).
- Per-job `actions/cache` of `.gradle/configuration-cache` (key
  `cc-<job>-<os>-<run_id>` + prefix restore-keys) — setup-gradle@v4 cannot
  see the project-local store. Stale restores are safe: fingerprint mismatch
  is an invalidation, not a problem.
- `configuration-cache` report uploaded as artifact `if: failure()`
  (`**/build/reports/configuration-cache/**`).
- `mcp-server-check` now builds `:mcp-server:jar` and runs
  `-Ptest.tags=fast,slow` — the E2E suite is no longer self-skipped in CI.

### A5 — `problems=fail`

`org.gradle.configuration-cache.problems=fail` in `gradle.properties`
(the issue's "не ставить warn глобально" — fail instead). Verified green under
the new mode: `:shared:jvmTest + :desktopApp:test`, `:androidApp:assembleDebug`,
`:shared:detekt + :desktopApp:detekt`, `:mcp-server:build -Ptest.tags=fast,slow`,
`:shared:koverXmlReport`.

Doc drift fixed alongside: `2026-10-03-kotlinx-coroutines-debug` §Implementation
still claimed `-javaagent` goes through `jvmArgumentProviders`; the code
deliberately resolves the agent path **eagerly as a plain String** because a
`CommandLineArgumentProvider` anonymous class captures the DSL script and
breaks CC — the file's own "CC fix — final" section already said so.

## Measurements (after)

| Run | Result |
|---|---|
| `:shared:jvmTest :desktopApp:test` first store under `problems=fail` | 3m 17s |
| same command, steady state | **6s**, "entry reused" |
| `:mcp-server:build -Ptest.tags=fast,slow` rerun | 0.6–4s, "entry reused" |
| `:androidApp:assembleDebug` store | 1m 34s |

## Rationale

A CC problem that only warns will rot until a Gradle upgrade turns it into a
hard failure on an unrelated PR. Failing immediately keeps the (already green)
debt at zero; CI caching + report upload makes the failure diagnosable from
the PR page. The stale-test workaround was actively misleading agents into
`--rerun-tasks` rituals; two-way evidence beats folklore.

## Consequences

- Any new CC-incompatible pattern (capturing `project` in a task action,
  undeclared inputs) fails the build/PR immediately.
- Fresh CI runs pay one `store`; subsequent runs reuse the cached store via
  `actions/cache` (entries from older Gradle/JDK simply do not match).
- Pre-v32 databases can upgrade again; `Migration31To32Test` pins the
  backfill-before-drop ordering; `McpServerEndToEndTest` (now isolated and
  CI-enabled) pins server startup + tool roundtrip.
- E2E spawn isolation means tests can never touch a developer's real data.
- `TOOL_ANNOTATIONS` keys must match Koog descriptor names verbatim — the
  E2E `tools/list` assertions make silent drift visible again.
- No OpenSpec change: infrastructure-only (skip_specs), no user-visible
  behavior change.

## Links

- Issues: [#29 CC hardening](https://github.com/gazon1/singularity-clone-kmp/issues/29)
- `docs/decisions/2026-10-01-test-infra-gaps.md` §2 (stale-test claim revoked)
- `docs/decisions/2026-10-03-kotlinx-coroutines-debug.md` (javaagent doc drift fixed)
- `docs/decisions/2026-10-04-navigation-policy.md` (Part B, this epic)
