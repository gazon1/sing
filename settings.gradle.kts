import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

@Suppress("UnstableApiUsage")
rootProject.name = "singularity-todo"

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
// ── The population the run measures ─────────────────────────────────────────
//
// A coverage floor describes a MEASUREMENT, and the measurement depends on the
// population that ran: the fast population exercises fewer classes, so it
// measures less coverage. Two populations therefore need two sets of floors —
// one set that describes a fast run and one that describes a full run.
//
// The population is read from the SAME property the tests use
// (`-Ptest.population`), so there is exactly one source of truth. A separate
// `-Pcoverage.profile` could disagree with the population the tests ran, and
// then the floor would be checked against a run that never happened.
//
// The name is turned into the tag list by `config/test-populations.properties`
// — the same file the root build script reads to hand the modules their tag
// list. The floor therefore cannot disagree with the selection: both read one
// definition.
//
// A population that selects `slow` is the full one. An absent population means
// the default run, which excludes `slow` and therefore measures the fast
// population.
val testPopulations = java.util.Properties().apply {
    rootDir.resolve("config/test-populations.properties").inputStream().use { load(it) }
}
val testPopulation = providers.gradleProperty("test.population").getOrElse("")
val testTags = providers.gradleProperty("test.tags").getOrElse("")
val selectedTags = when {
    testPopulation.isNotEmpty() -> {
        val declared = testPopulations.getProperty(testPopulation)
        require(declared != null) {
            "unknown test population '$testPopulation' — the known ones are: " +
                testPopulations.stringPropertyNames().sorted().joinToString(", ") +
                " (config/test-populations.properties)"
        }
        declared
    }
    else -> testTags
}.split(",").map { it.trim() }.filter { it.isNotEmpty() }
val measuredFullPopulation = selectedTags.any { it == "slow" }

// Floors rounded down from measured values, one set per population.
//
// Named constants, not bare literals, because the minimum acceptable floor is policy and the
// measured value is a fact — mixing them in one number means raising the floor looks
// like a measurement and lowering it looks like a rounding. The `require` block is the
// same contract as DETEKT_RULE_BRANCH_FLOOR's in detekt-rules/build.gradle.kts: the build
// file is the claim, and the claim is checked where it is made.
//
// FULL population — the floor this repository carried before the split, measured
// 2026-10-15: 27.6 instruction / 20.7 branch / 27.4 line.
//
// The floor stays at those numbers, deliberately, and the next paragraph says why
// that is a weaker claim than the label above used to make: a later full run
// (2026-10-22, `-Ptest.tags=fast,slow`) measured 55.89 / 40.45 / 57.23 — roughly
// twice as much — and the 2026-10-15 figures are almost exactly what the FAST
// population measures today (27.73 / 23.23 / 31.26), which says the 2026-10-15
// "full" measurement was not a full-population measurement.
//
// So this floor is a floor the fast population's own measurement justifies,
// applied to the full population as well: a number both populations meet, rather
// than a number measured on this one. It is deliberately not the strictest
// reading of the requirement — a floor rewritten only from a measurement of its
// own population — and that gap is stated here rather than hidden in a label.
//
// Raising the floor to the newer numbers is a POLICY decision, not a measurement:
// the floor is the minimum acceptable coverage, and the nightly would then fail on
// a fraction of a percentage point of drift. This change makes the floor real —
// before it, the rule was never evaluated at all (see the ADR of 2026-10-22) — and
// a conservative floor that is enforced beats a strict one that is a promise. The
// question of tightening it is recorded in that ADR as an open decision.
val FULL_INSTRUCTION_COVERAGE_FLOOR = 27
val FULL_BRANCH_COVERAGE_FLOOR = 20
val FULL_LINE_COVERAGE_FLOOR = 27

// FAST population — measured 2026-10-22 on a `-Ptest.tags=fast` run over the same set of
// tasks CI runs: 27.73 instruction / 23.23 branch / 31.26 line.
//
// The measurement is a real aggregate and not a partial one: the report holds 4634
// classes over 2.82M instructions, against the full run's 4642 classes over the same
// 2.82M instructions. The same class universe, less coverage — which is what a
// narrower population is supposed to look like. (A report that had measured a fifth
// of the codebase would have shown a fifth of the denominator too; that check is
// what makes this number usable as a floor.)
val FAST_INSTRUCTION_COVERAGE_FLOOR = 27
val FAST_BRANCH_COVERAGE_FLOOR = 23
val FAST_LINE_COVERAGE_FLOOR = 31

require(FULL_INSTRUCTION_COVERAGE_FLOOR >= 27) {
    "app instruction coverage floor must not drop below 27% — measured 27.6% on 2026-10-15"
}
require(FULL_BRANCH_COVERAGE_FLOOR >= 20) {
    "app branch coverage floor must not drop below 20% — measured 20.7% on 2026-10-15"
}
require(FULL_LINE_COVERAGE_FLOOR >= 27) {
    "app line coverage floor must not drop below 27% — measured 27.4% on 2026-10-15"
}
require(FAST_INSTRUCTION_COVERAGE_FLOOR >= 27) {
    "app fast-population instruction coverage floor must not drop below 27% — " +
        "measured 27.73% on 2026-10-22"
}
require(FAST_BRANCH_COVERAGE_FLOOR >= 23) {
    "app fast-population branch coverage floor must not drop below 23% — " +
        "measured 23.23% on 2026-10-22"
}
require(FAST_LINE_COVERAGE_FLOOR >= 31) {
    "app fast-population line coverage floor must not drop below 31% — " +
        "measured 31.26% on 2026-10-22"
}

// The floors that apply to THIS run.
val appInstructionCoverageFloor =
    if (measuredFullPopulation) FULL_INSTRUCTION_COVERAGE_FLOOR else FAST_INSTRUCTION_COVERAGE_FLOOR
val appBranchCoverageFloor =
    if (measuredFullPopulation) FULL_BRANCH_COVERAGE_FLOOR else FAST_BRANCH_COVERAGE_FLOOR
val appLineCoverageFloor =
    if (measuredFullPopulation) FULL_LINE_COVERAGE_FLOOR else FAST_LINE_COVERAGE_FLOOR

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
    // `:adr-cli` is the ADR toolchain's command line — build tooling, like
    // `:detekt-rules` above. Its classes are not in the app's coverage
    // denominator today (it is not built during the coverage job, so there is
    // nothing to instrument), and this line says so instead of leaving it to
    // that accident: the day `:adr-cli` becomes a dependency of the app, its
    // classes would otherwise enter the denominator with no test task behind
    // them, and the report would score them 0%.
    //
    // `scripts/check-coverage-task-list.py` is what keeps the two lists
    // honest against each other: every project with tests is either measured
    // by the report or excluded from its scope.
    skipProjects(":detekt-rules", ":adr-cli")
    instrumentation {
        includedClasses.add("com.singularity.todo.*")
    }

    // ── The app coverage floors ─────────────────────────────────────────────
    //
    // These live HERE, in the aggregation plugin's own `reports { verify { } }`,
    // and not in the root build script. The reason is not a preference: the
    // root project has no Kover extension of its own to configure. The
    // aggregation plugin registers `:koverXmlReport`, `:koverHtmlReport` and
    // `:koverVerify` on the root and reads ITS OWN settings-level rules —
    // a `plugins.withId("org.jetbrains.kotlinx.kover") { configure { … } }`
    // block in the root build script never runs at all (probed: the block's
    // `println` never appeared), so the floors it declared were never
    // evaluated. The rule lives where the plugin reads it.
    //
    // A floor belongs to a NAMED POPULATION, not to the project. A run that
    // selected fewer classes measures less coverage, so one floor cannot
    // describe both a fast run and a full run: it is either trivially
    // satisfied by the fast one or unreachable by it.
    //
    // The population is read from the SAME property the tests use
    // (`-Ptest.tags`), so there is exactly one source of truth. A separate
    // `-Pcoverage.profile` could disagree with `-Ptest.tags`, and then the
    // floor would be checked against a run that never happened.
    //
    // Each floor is written down only from a measurement of ITS OWN
    // population, and the comparison stays one-sided: a fuller population
    // passing a narrower population's floor is legitimate, not a defect.
    reports {
        verify {
            rule("app instruction coverage") {
                filters { includedClasses.add("com.singularity.todo.*") }
                bound {
                    minValue.set(appInstructionCoverageFloor)
                    coverageUnits.set(CoverageUnit.INSTRUCTION)
                }
            }
            rule("app branch coverage") {
                filters { includedClasses.add("com.singularity.todo.*") }
                bound {
                    minValue.set(appBranchCoverageFloor)
                    coverageUnits.set(CoverageUnit.BRANCH)
                }
            }
            rule("app line coverage") {
                filters { includedClasses.add("com.singularity.todo.*") }
                bound {
                    minValue.set(appLineCoverageFloor)
                    coverageUnits.set(CoverageUnit.LINE)
                }
            }
        }
    }
}

include(":androidApp")
include(":desktopApp")
include(":shared")
// The ADR toolchain's dependency chain, innermost first. `:adr-cli` depends on
// `:adr-core` and never on `:shared` — that edge is what made a markdown lister ship
// Compose, Room, SQLite and Koog (#522 follow-up). See ADR
// 2026-10-11-adr-storage-outlives-the-application.
include(":core-base")
include(":adr-core")
include(":mcp-server")
include(":adr-cli")
include(":detekt-rules")
// A KSP processor, its own module: a detekt rule is handed one file at a
// time and this question spans files. Not yet applied to :shared.
include(":tools:unwritten-properties")

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
