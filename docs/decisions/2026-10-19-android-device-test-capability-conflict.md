---
title: "The device-test source set had two providers of one test-framework capability, and the source set was empty"
date: 2026-10-19
status: accepted
tags: ["build", "gradle", "kotlin-multiplatform", "testing"]
---

## Context

Work on the toolchain in this repository (adding a Gradle plugin, then reverting
it) turned up a build failure that had nothing to do with either: `:shared`'s
`androidDeviceTest` source set could not resolve its own classpath.

The error is a capability conflict:

```
Cannot select module with conflict on capability
'org.jetbrains.kotlin:kotlin-test-framework-impl:2.4.10'
```

`shared/build.gradle.kts` declares, in `commonTest`:

- the umbrella `kotlin-test`, which the Kotlin Gradle Plugin adds for variant
  inference, and
- an explicit `kotlin-test-junit5`, because the common tests are JUnit 5.

`androidDeviceTest` inherits `commonTestImplementation`, so its classpath carries
both. The umbrella resolves to the **JUnit 4** variant there — the device tests
run under `AndroidJUnitRunner` — and `kotlin-test-junit` and `kotlin-test-junit5`
both provide the `kotlin-test-framework-impl` capability. Two providers of one
capability in one configuration is a resolution failure, not a warning.

## Idea

Leave the umbrella in place (the Kotlin Gradle Plugin needs it for variant
inference) and exclude the JUnit 5 artifact from the device-test configurations
only, so exactly one provider of the capability remains.

## Decision

```kotlin
configurations.matching { it.name.startsWith("androidDeviceTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit5")
}
```

The scope is by configuration **name**, and that is the load-bearing detail. The
first version of this fix was

```kotlin
sourceSets.configureEach {
    configurations.configureEach { exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit5") }
}
```

which looks more precise but is not: inside a `sourceSets` block, `configurations`
resolves to the **project's** configurations, not the source set's. The exclusion
therefore applied to every configuration in the project, `kotlin-test-junit5`
disappeared from `commonTest` as well, and every test file in the module failed to
compile with `Unresolved reference 'Test'`. The fix was rewritten to select by
configuration name, and the file comment records the trap so the next reader does
not re-introduce it.

## Rationale

- **The defect is pre-existing and unrelated to the work that surfaced it.** It
  reproduces on a clean checkout with no plugin added, which is why it is recorded
  here rather than attributed to the change that revealed it.
- **It is latent, and latent is a timing question, not a safety one.**
  `shared/src/androidDeviceTest` contains no Kotlin files, so no ordinary build
  task resolves that classpath and the conflict stays dormant.
  `:shared:kspAndroidDeviceTest` does resolve it, and it failed — with or without
  any other plugin in the build. The first device test written into that source
  set would have arrived with an unresolvable classpath, and the failure would
  have been attributed to the new test rather than to the build.
- **Excluding the JUnit 5 artifact, not the umbrella.** The umbrella is what the
  Kotlin Gradle Plugin adds for variant inference; removing it from the device
  source set would leave the plugin to guess. What is wrong is the *second*
  provider, and the surviving provider is the JUnit 4 variant the device tests
  actually run under.
- **The exclusion is scoped to the device-test configurations by name.** The
  project-wide version of the same fix broke every test in the module, which is
  the fastest possible way to learn that `configurations` inside `sourceSets` is
  not the source set's.

## Consequences

- `:shared:kspAndroidDeviceTest` resolves and builds successfully. The device
  test classpath has exactly one provider of `kotlin-test-framework-impl`.
- **No test source set changes behaviour.** `commonTest`, `jvmTest` and
  `androidHostTest` keep `kotlin-test-junit5`; only the `androidDeviceTest*`
  configurations lose it.
- **The device-test source set is still empty.** This fix makes the source set
  *usable*; it does not add device tests, and nothing in this change should be
  read as a statement that the source set is exercised.
- **The comment in `shared/build.gradle.kts` is part of the fix.** A bare
  `exclude(...)` with no explanation is the kind of line that gets removed as
  "redundant" by the next person to hit a resolution error elsewhere.

## Links

- `shared/build.gradle.kts` — the exclusion and its comment
- `gradle/libs.versions.toml` — `kotlin = "2.4.10"`, the version the conflict
  was reported against
