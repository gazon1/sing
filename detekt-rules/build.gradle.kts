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

// JUnit Platform — same configuration as shared/desktopApp/mcp-server.
// Bumped heap to 2 GB for consistency; detekt-test loads compiled rule classes.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "3g"
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
// The actual floor enforcement is in scripts/check-detekt-rule-coverage.py rather than
// koverVerify, because kover 0.9.x's Gradle DSL for verify{} is not stable across
// versions. The Python gate runs the report, parses branch coverage, and fails below 60%.
kover {
    reports {
        total {
            html { onCheck = false }
            xml { onCheck = false }
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
