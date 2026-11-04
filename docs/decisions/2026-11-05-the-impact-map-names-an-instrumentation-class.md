---
title: "The impact map names an instrumentation class by the runner argument"
date: 2026-11-05
status: accepted
tags: ["tooling", "tests", "android"]
---

## Context

`scripts/test-impact-map.py` answers "which test classes can this change
break?". It knew five test source sets — `shared/src/commonTest`,
`shared/src/jvmTest`, `shared/src/androidHostTest`, `desktopApp/src/jvmTest`
and `mcp-server/src/test` — and the Android instrumentation tier
(`androidApp/src/androidTest`) was in none of them.

The tier is the only one that sees the real Android runtime: the real
`MainActivity`, the auth bypass, `EncryptedSharedPreferences` over the Android
Keystore, the notification manager, and the `testTag` → UiAutomator
`resource-id` mapping. A change to the Android shell between the pull-request
smoke and the nightly reached no instrumentation test at all, because the map
had no way to name one: the tier's classes lived in a source set the map did
not know, and they ran under a task the map had never heard of.

Two things were missing, and they are two different things:

1. the source set and the task that runs it — `:androidApp:connectedDebugAndroidTest`,
   the task the device job already invokes;
2. the way a class is NAMED for that task — `--tests` is a Gradle test filter
   and is not understood by the instrumentation runner.

## Decision

The map learns the instrumentation tier, and it names its classes the way the
tier's own runner does.

* `androidApp/src/androidTest` joins the test source sets, with
  `:androidApp:connectedDebugAndroidTest` as its task.
* The class filter of a task that does not take `--tests` is a RUNNER
  ARGUMENT: `-Pandroid.testInstrumentationRunnerArguments.class=…`, the
  spelling the device smoke already uses
  (`.github/workflows/android-device-tests.yml`). The runner's `class`
  argument takes a comma-separated list, so one invocation names every class
  of the task, the way one `--tests` filter names every class of a JVM task.
* The POPULATION does not narrow that task. The runner takes a list of classes
  and runs what it is given; there is no tag filter to narrow it with. The
  tier's classes are untagged by design — they are JUnit4 classes on the
  Vintage engine, where a Jupiter `@Tag` is not a filter — so a population
  that dropped them would drop a class the runner WOULD run. The command of
  that task therefore names no population: the property would be read by
  nothing, and naming it would say the population narrows the task when it
  does not.
* A class of that tier is named only when it declares a test member. The
  tier's base class (`InstrumentedTestBase`) is named like a test and carries
  no test method; given to the runner it is an initialization error, so the
  map must not name it. The classes that extend it are the tests that run,
  and a change to that harness selects them.

## Rationale

The runner argument is not a new spelling invented for the map: it is the
spelling the repository already uses to run one class of this tier on a
device, in CI and in the runbook. A map that names the class any other way
would produce a command the tier has never been asked to run.

The population exemption is the same argument the map already makes for
`:shared:testAndroidHostTest`: a population is a list of JUnit tags, and the
filter that reads it belongs to the task. Where the task has no such filter,
the population cannot narrow it, and the map says so rather than dropping a
class the runner would have run.

## Consequences

* A change to the Android shell now names the instrumentation classes it can
  reach, and the command that runs them is a real one.
* The map can name an instrumentation class but not a JVM class in the same
  invocation: the two filters are different, so the two tiers are two
  commands, as they were before.
* The tier's classes are untagged, so the repository's tag-coverage gate
  (`TestTagCoverageTest`) does not cover them — the same exemption
  `shared/src/androidHostTest` has, and for the same reason.
