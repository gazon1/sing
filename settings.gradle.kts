@Suppress("UnstableApiUsage")
rootProject.name = "Singularity_cllone_kmp"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // Map plugin id "koin" → module "io.insert-koin:koin-gradle-plugin".
    // Koin 4.x publishes the compiler as a plain Gradle plugin JAR without a
    // plugin-marker artifact, so the plugins DSL alone can't resolve it.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "koin") {
                useModule("io.insert-koin:koin-gradle-plugin:${requested.version}")
            }
            // The compiler plugin (io.insert-koin.compiler.plugin) also lacks a
            // plugin-marker artifact in some releases; resolve it explicitly.
            if (requested.id.id == "io.insert-koin.compiler.plugin") {
                useModule("io.insert-koin:koin-compiler-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JetBrains Koog AI agent framework
        maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Settings-level Kover. The version must match `kover` in gradle/libs.versions.toml;
    // `scripts/build-version-catalog-gate.py` fails the build if the two drift.
    // The `.aggregation` id is the settings-level variant: the plain id resolves to the
    // project plugin, which Gradle refuses to apply to a Settings object.
    id("org.jetbrains.kotlinx.kover.aggregation") version "0.9.9"
}

// Coverage is measured across every test JVM in the build, not per project.
//
// The reason is a measured one: `desktopApp` has 27 Compose flow tests that render the
// real shared screens, and every one of them was invisible to the number. Per-project
// Kover instruments only that project's own classes, so `:desktopApp:koverXmlReport`
// reported 3 classes / 220 instructions / 0 covered while those tests were executing
// ~80k instructions of shared UI. A settings-level report aggregates them, which makes
// "0% on this package" mean "no test touches it" instead of "no test in this project
// touches it".
//
// The plugin registers `koverXmlReport` / `koverHtmlReport` / `koverVerify` on the root
// project, writing to `build/reports/kover/report.xml`. Its settings-level `reports { }`
// block only takes filters and verification rules — there is no `total { }` there, and
// asking for one fails script compilation with "Unresolved reference".
//
// `enableCoverage()` is mandatory and is NOT implied by the block below.
// `KoverSettingsExtensionImpl.coverageIsEnabled` is `convention(false)`, and nothing in
// the settings DSL flips it: `KoverSettingsGradlePlugin` reads it in `beforeProject` and
// returns early when false, so the root tasks are simply never registered. Symptom of
// forgetting it: the build succeeds, every test task runs with the Kover agent attached,
// per-project `bin-reports/*.ic` appear, and `:koverXmlReport` does not exist — the
// subproject plugins were doing all the instrumenting. Confirmed against the 0.9.9
// sources, not inferred.
//
// The `includedClasses` filter is the same one the per-project blocks used, and for the
// same reason (ADR 2026-09-25-test-jvm-heap-default): the IntelliJ coverage runtime
// keeps one ClassData entry per loaded class, and the Koog classpath alone contributes
// 3000+ of them.
kover {
    enableCoverage()
    // Build tooling, not application code. `detekt-rules` is the custom-rule
    // module; its rules are exercised by 56 tests, but no CI job and no check.sh
    // step runs them, and 4 of those tests fail on this branch for reasons that
    // predate it (`:detekt-rules:test` is not part of any gate). Including the
    // module would put custom-detekt classes into the app coverage denominator —
    // a different question from the one the floor asks — and would make the
    // aggregated report depend on a suite that is not currently maintained.
    // The failing tests are recorded as an open finding, not hidden by this line.
    skipProjects(":detekt-rules")
    instrumentation {
        includedClasses.add("com.singularity.todo.*")
    }
}

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":mcp-server")
include(":detekt-rules")

// The source-available `pro` catalogue (FSL-1.1-ALv2, see LICENSE.pro).
//
// Conditional on purpose, and this is the single most important line in the file for the
// open-core model. The project's rule is that **the public repository builds and passes
// every gate without `pro/`** — and an unconditional include would make the free
// configuration unbuildable, which would make the claim true only on paper. It also
// means a fresh clone resolves no proprietary artefact, so the Apache-2.0 build needs no
// credentials and no private repository.
//
// The price is that the code in `pro/` compiles only when this include is active, so CI
// has to run both configurations. `scripts/check-pro-licence-boundary.py` is what keeps
// that honest: it proves the free configuration really is free, which is otherwise a
// claim nothing would notice being false.
val withPro: Boolean = providers.gradleProperty("withPro")
    .orElse("false")
    .map { it.toBoolean() }
    .get()
if (withPro) {
    include(":pro")
}
