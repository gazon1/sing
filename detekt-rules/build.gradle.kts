import java.util.Properties
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    kotlin("jvm")
    // Added 2026-10-05. The rule-coverage debt for this module was tracked as prose in
    // deferred-backlog.md, which nothing could verify. Kover turns it into a number CI
    // can defend. See ADR 2026-10-05-positive-tests-for-every-detekt-rule.
    //
    // `id(...)` and NOT `alias(libs.plugins.kover)`. settings.gradle.kts applies
    // `org.jetbrains.kotlinx.kover.aggregation` (main, 7bba7678), which puts the Kover
    // plugin on the classpath with no resolvable version. Asking for it again with the
    // version attached fails configuration outright — "the plugin is already on the
    // classpath with an unknown version" — which took :detekt-rules:test down with it
    // even though the test task has nothing to do with coverage. No version means
    // "use the one that is already here", which is the intent.
    id("org.jetbrains.kotlinx.kover")
    // Added 2026-10-05: the module that enforces the project's rules is now subject to
    // them. A `var` on a Rule instance — "Common Mistake 4" in
    // singularity-todo-detekt-rules-authoring, and a genuine bug because Rule instances
    // are reused across files — had nothing checking it in this module.
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // detekt-api — version sourced from the catalog (version.ref = "detekt"),
    // same version as the project's detekt plugin so the rule API is
    // binary-compatible with :shared's/:desktopApp's detekt runs.
    implementation(libs.detekt.api)
    // detekt-test for writing tests against the rule (optional, add as needed)
    testImplementation(libs.detekt.test)
    testImplementation(libs.detekt.test.utils)
    testImplementation(kotlin("test"))
    // ktlint via detekt-formatting, so `just detekt-fix` also formats this module.
    detektPlugins(libs.detekt.formatting)
}

// ── The memory budget ───────────────────────────────────────────────────────
//
// The heap of a test fork is a MEASUREMENT, and the measurements live in
// `config/docs/gradle-memory-budget.properties` — the record, one file, read by
// the build. The tables and the failed measurements behind it are in
// `docs/decisions/2026-10-22-gradle-memory-budget.md`.
//
// This module reads its OWN line of the record. Its line is 3 GB, and that is
// NOT a measurement of the 2026-10-22 measurement: it is the value this module
// declared before the record existed, recorded there so that the gate has one
// place to compare it with. `scripts/check-memory-budget.py` is what keeps the
// two in step.
val memoryBudget = Properties().apply {
    rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
}
val testTaskHeap = memoryBudget.getProperty("test.heap.module.detekt-rules")
    ?: throw GradleException(
        "config/docs/gradle-memory-budget.properties declares no test.heap.module.detekt-rules — " +
            "the heap of this module's test tasks has to be recorded somewhere."
    )

// JUnit Platform — same configuration as shared/desktopApp/mcp-server.
// The heap is the record's — 3 GB, the value this module declared before the
// record existed. (The comment that used to sit here said "2 GB"; the value
// was 3 GB, and the comment was the wrong one.)
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = testTaskHeap
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
    )

    // DetektConfigWiringTest reads config/detekt/detekt.yml at runtime to cross-check the
    // config against the registered providers. Gradle cannot see that dependency, so
    // without this the test task stays UP-TO-DATE when detekt.yml changes — meaning the
    // gate would not fire on exactly the edit it exists to catch. Verified: editing the
    // config alone produced BUILD SUCCESSFUL until this input was declared.
    inputs.file(rootProject.file("config/detekt/detekt.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(
        layout.projectDirectory.file("src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}

// kover — rule coverage
//
// Every custom rule is exercised by at least one positive test (RuleFiresSmokeTest), so
// the coverage this measures is *branch* coverage inside the rules, not "is the rule
// wired up". That is the number that matters: the two rules found to be no-ops on
// 2026-10-05 both had 100%-of-file coverage in the sense that mattered and zero
// behavioural coverage.
//
// The floor is enforced by the native `:detekt-rules:koverVerify` task: ci.yml's
// "Kover verify (detekt-rules)" step names it, `./check.sh` names it, and `check`
// (where `verify.onCheck` defaults to true for the total variant) runs it too.
// The earlier claim that kover 0.9.x's project-level Kotlin DSL has no `verify{}`
// binding was wrong — checked against the 0.9.9 sources, `reports { total { verify {
// rule { bound { } } } } }` is exactly the API below.
//
// What renders the number is `:detekt-rules:koverLog` (the `log {}` block below):
// ci.yml's advisory summary step greps its single line, and check.sh prints the same
// line before verifying. Both replaced scripts/detekt-rule-coverage.py, a Python parser
// of this XML whose own input was only produced by the gate step — so the summary
// had been rendering its "report not found" failure all along. Why the script and
// its copy of the floor are gone:
// docs/decisions/2026-10-15-kover-floor-lives-in-the-build-file.md
//
// Two controls hold the declaration instead of a second copy of the number. Part C of
// check-gate-wiring.py (kover_verify_rules_without_usable_floor) reads the
// *declaration*: a bound it cannot resolve to at least _KOVER_MIN_FLOOR, or a
// `minValue` that lives outside the `kover { }` block below, is a finding — the same
// way a detekt block with ignoreFailures = true is one. The SABOTAGE control
// `detekt-rule-coverage-floor` in that same file raises the constant in
// settings.gradle.kts to 100 and requires koverVerify to fail, because nothing
// Python-side reads the bound at run time and therefore nothing Python-side can show
// the task honours it. It is the only control there that runs Gradle, and the module's
// tests run before it in both surfaces that call that script.
//
// Branch coverage, not line: a guard that never fires is the failure this measures,
// and `just tkr` (`:detekt-rules:koverXmlReport :detekt-rules:koverLog`) prints
// the per-rule table — its branch section generates the list that sits beside the
// floor constant in settings.gradle.kts.

// The floor itself is DECLARED in settings.gradle.kts — one place for every
// coverage floor of this repository — and this module only binds it, because Kover
// reads the verify rule here and nowhere else. The value travels through
// `gradle.extra`, the channel a settings script and a module's build script share;
// a missing value is a configuration failure that says what to declare, not a floor
// that silently defaults to zero.
//
// The declaration, its measurement and the list of the worst rules by missed branch
// live beside the constant in settings.gradle.kts.
val DETEKT_RULE_BRANCH_FLOOR: Int =
    (gradle.extra["kover.detektRulesBranchCoverageFloor"] as? Int)
        ?: error(
            "settings.gradle.kts must declare the detekt-rules branch coverage floor and " +
                "publish it as gradle.extra[\"kover.detektRulesBranchCoverageFloor\"] — the " +
                "floor is declared in one place, and this module only binds it."
        )

kover {
    reports {
        total {
            html { onCheck = false }
            xml { onCheck = true }   // generate report during test/check phase
            verify {
                rule("detekt-rules branch coverage") {
                    bound {
                        minValue = DETEKT_RULE_BRANCH_FLOOR
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
            }
            // Printed by `:detekt-rules:koverLog` — the number ci.yml's summary step
            // renders. Same constant, same unit as the rule above.
            log {
                coverageUnits = CoverageUnit.BRANCH
                format = "detekt-rules branch coverage: <value>%"
            }
        }
    }
}

// detekt on detekt-rules itself.
//
// Uses the shared config *without* the custom rule sets: those rules are defined here, and
// a module cannot apply its own rules to itself before they are built. The built-in rules
// still apply, and the custom rules are covered by :detekt-rules:test instead.
detekt {
    // Generated from detekt.yml by scripts/gen-detekt-rules-config.py, which drops only
    // the custom rule-set blocks. Everything else is shared config, single-sourced.
    config.setFrom(rootProject.file("config/detekt/detekt-rules-module.yml"))
    buildUponDefaultConfig = true
    ignoreFailures = false
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}
