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
  spelling the device job already uses
  (`.github/workflows/android-device-tests.yml`). The runner's `class`
  argument takes a comma-separated list, so one invocation names every class
  of the task, the way one `--tests` filter names every class of a JVM task.
* The COMMAND the map prints for that task is the repository's own runner,
  `python3 scripts/run-android-ui-tests.py --class <fqcn,…>`. That script is
  what makes the class filter real: it starts or reuses a device, assembles
  this checkout's debug APK, forwards the class filter as the runner argument,
  and fails a run that executed zero tests. A bare
  `:androidApp:connectedDebugAndroidTest` is not a command that passes as
  printed — it needs a device that exists and a report that is parsed, not
  trusted — and the fast lane is exactly where a caller runs one verbatim.
* The POPULATION does not narrow that task. The runner takes a list of classes
  and runs what it is given; there is no tag filter to narrow it with. The
  tier's classes are untagged by design — they are JUnit4 classes on the
  Vintage engine, where a Jupiter `@Tag` is not a filter — so
  a population that dropped them would drop a class the runner WOULD run. The
  command of that task therefore names no population: the property would be
  read by nothing, and naming it would say the population narrows the task when
  it does not.
* A class of that tier is named when it is a class the runner can
  INSTANTIATE. The rule is abstractness, not the presence of a `@Test` of the
  class's own: the tier's classes inherit their test methods from
  `InstrumentedTestBase`, and a JUnit4 `TestCase` names its tests `testXxx`
  with no annotation at all — so requiring an own `@Test` dropped exactly the
  classes the tier exists to run. The base class itself is `abstract`, which
  is what keeps it out: given to the runner it is an initialization error, not
  a run. A change to that harness selects the classes that extend it.
* A name mentioned by another class is a COUPLING only when it is mentioned in
  the class's code, as a name of its own. The tier's helpers are single common
  words (`UiActions`, `StepRecorder`, `ShellProbe`), so a substring match finds
  them inside longer identifiers and inside comments that merely explain a
  helper — the case where the idiom the map uses elsewhere misfires. A helper
  file may declare no class at all — `UiActions.kt` and `ShellProbe.kt` are
  extension functions — so the top-level function names of such a file feed the
  same rule; reading class declarations alone selected nothing for the files
  every UI test drives the device through.

## Rationale

The runner argument is not a new spelling invented for the map: it is the
spelling the repository already uses to run one class of this tier on a
device, in CI and in the runbook. A map that named the class any other way
would produce a command the tier has never been asked to run.

Naming the SCRIPT rather than the Gradle task follows from the same argument.
The map's answer is "these are the tests to run", and an answer whose command
cannot be run is not an answer: the device, the APK and the report parsing are
all part of what "run this class" means for this tier. The one place that knows
that is the runner the device job already calls, so the map hands it the
classes instead of restating three of its steps badly.

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
* A class of the tier whose NAME does not say `Test` and that declares no
  member of its own is still invisible: the map reads source text and cannot
  see a superclass declared in another file. The tier's convention is `*Test`,
  and the limit is stated where the rule lives rather than left to be
  discovered.
* The tier's class list is all-or-nothing: one class the runner cannot
  instantiate fails the whole invocation. What keeps that safe is that the map
  only ever names a non-abstract class of that source set, which is pinned by
  a test that lists the tier's runnable classes by name.
* `scripts/run-android-ui-tests.py` gains `--class`, which is `GRADLE_ARGS`'s
  class filter in a shape a caller can pass — appended to, not replacing, the
  arguments the device job already passes.
* The lanes without a device NAME that command instead of executing it:
  `check.sh --fast` prints it as "on a device" and counts the classes it names,
  and the CI `quick` job does the same. Appending `--rerun` or the coverage
  property to a script that does not know them would fail the lane, and
  starting an AVD inside the fast lane is not what the lane is for. The device
  job remains the place the tier runs; what changed is that the answer is now
  a class list rather than "all of them".
