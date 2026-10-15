---
title: "JDK 21 toolchain pin, -Xss4m probe, and Koin entry-scope hoist (#413/#414/#415)"
date: 2026-10-14
tags: [desktop, android, di, koin, testing, gradle, jdk]
status: accepted
---

# JDK 21 toolchain pin, -Xss4m probe, and Koin entry-scope hoist

## Context

Issues #413/#414/#415 attribute a startup `StackOverflowError` in desktop and Android
flow tests to running on ambient JDK 25 after the host upgrade. Facts established
before deciding anything:

- The Gradle **daemon** is pinned to Azul 21 since `b6394207` (2026-09-30), so test
  forks inherit 21; only the `gradlew` launcher on this host runs 25. The
  "everything runs on the ambient 25" premise never matched the build configuration.
- At `03270a51`→`3d60e393` the full desktop suite (`-Ptest.tags=fast,slow`,
  135 tests) passes **on JDK 21 and, via an init script forcing the test JVM,
  on JDK 25**: zero failures of any kind, and no `StackOverflowError` in any
  `build/diagnostics` bundle.
- The failing-test list in #413 is stale: 5 of its 8 classes no longer exist
  (`AgendaWithPomodoroFlowTest`, `FullAgendaFlowTest`, `TagsFlowTest`,
  `SearchFlowTest`, `SettingsFlowTest`).

So there is no reproducing failure to fix, but the ordered work still has to decide
how to harden the build and what to do with the one structural candidate (#414's
`koinInject` inside entry content).

## Idea

Three hardening moves were on the table:

1. **Do nothing** — the daemon pin already yields 21; wait for a real reproduction.
2. **Explicit `jvmToolchain(21)`** in `shared` + `desktopApp`, plus an `-Xss4m`
   probe in the desktop test JVM.
3. **Structural navigation refactor** (per the original order: only if the probe
   is insufficient) — defer unless evidence demands it.

For #414 specifically: hoist `koinInject<PomodoroTimer>` / `koinInject<PomodoroTaskListProvider>`
out of the `entry { }` content, choosing between body-hoist (Android's expression-body
`createAppEntryProvider` would force a block-body conversion and a ~140-line re-indent),
call-site injection (duplicated at both shells), and **composable default parameters**
on the provider functions.

## Decision

1. **`jvmToolchain(21)` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts`.**
   Compilation, test forks and `run` resolve 21 through the toolchain service instead of
   inheriting whichever JVM launched Gradle. Android's explicit `jvmTarget = 11` stays
   authoritative for its bytecode.
2. **Keep `-Xss4m` in the desktop test JVM as a falsifiable probe**, not as a fix:
   a recurrence under a 4 MB stack kills the stack-depth hypothesis outright instead of
   quietly masking it.
3. **Hoist the two Pomodoro injections to shell composition via composable default
   parameters** on `createJvmEntryProvider` / `createAppEntryProvider`
   (`pomodoroTimer: PomodoroTimer = koinInject()`, `#414`). Both call sites stay
   unchanged; the provider signature now documents the dependency and lets tests
   override it. The larger NavDisplay refactor is **not** triggered — with no
   reproduction, the probe cannot come back "insufficient".
4. **Retag the three remaining `@Tag("fast")` full-harness classes to `slow`**
   (`PlatformParityTest`, `CalendarFlowTest`, `AgendaTabDefinitionFlowTest`) per the
   tag strategy: slow = crosses the process boundary. Floors re-recorded from a plain
   default run: `desktopApp:test 33 → 26`, `shared:jvmTest 2780 → 2785`
   (`--allow-drop`, drop explained exactly: 1+3+3 retagged tests).

## Rationale

- A toolchain pin is checkable at the project level; a daemon pin is an environment
  fact that silently disappears under a different launcher, an IDE, or a removed
  `gradle-daemon-jvm.properties`.
- The default-parameter form was chosen over the alternatives because it is the only
  one that changes neither the expression-body shape of the Android provider nor the
  two call sites, while still moving resolution from NavDisplay's deep entry-content
  composition to the shell.
- The hoist also fixes a real lifetime mismatch: the factory timer was recreated on
  every tab visit (state lost) while its ticker, on the injected **singleton**
  `CoroutineScope`, kept running on the discarded instance. One instance per shell
  composition makes visible state and ticker agree.
- `AndroidPomodoroTimer.init`'s `cancelPhaseEndAlarm()` now runs at shell composition
  (every app start) instead of on first tab open — consistent with the init's own
  stated purpose ("a stale alarm from a previous process instance must not fire").

## Consequences

- Local default desktop run shrinks 33 → 26 tests; CI (`fast,slow`, 135 tests) and the
  floors stay green. The three retagged classes no longer run in a plain `check.sh`.
- If the SO ever recurs **with** `-Xss4m` in place, the stack-depth theory is dead and
  #414 must be re-opened against a different mechanism.
- `createXEntryProvider` signatures gained two parameters — any future caller outside
  the two shells must supply or default them.
- main at merge time was red independently of this work (stale `SessionStore` import,
  outdated `App()` call in the desktop harness, 9 desktop flow failures from #614/#623);
  the two compile breaks are fixed on this branch and tracked in a separate issue.

## Links

- Issues: #413, #414, #415, #445 (deferred), #615, #616
- ADR `2026-10-01-desktop-nav-followup` (same hoisting principle, back stacks)
- `gradle/gradle-daemon-jvm.properties`, `config/docs/test-runs-baseline.txt`
