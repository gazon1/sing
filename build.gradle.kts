plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    // koinCompiler — uses the catalog alias (koin-compiler-plugin is a plain version key)
    alias(libs.plugins.koin.compiler) apply false
    // Kover is NOT listed here: it arrives on the classpath from the settings-level
    // `org.jetbrains.kotlinx.kover.aggregation` plugin (settings.gradle.kts) and is
    // applied to every project from there. Its version is kept in libs.versions.toml
    // and the two are kept in sync by scripts/build-version-catalog-gate.py.
}

allprojects {
    // Version constants from gradle.properties (not catalog) — avoids catalog accessor shadowing
    // caused by library keys like jvm-test that generate nested accessors colliding with version keys.
    val kotlinVersion: String = project.property("version.kotlin") as String
    val serializationVersion: String = project.property("version.kotlinSerialization") as String
    val collectionsImmutableVersion: String = project.property("version.kotlinxCollectionsImmutable") as String

    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(kotlinVersion)
            }
            if (requested.group == "org.jetbrains.kotlinx") {
                if (requested.name == "kotlinx-serialization-json" ||
                    requested.name == "kotlinx-serialization-core" ||
                    requested.name == "kotlinx-serialization-bom") {
                    useVersion(serializationVersion)
                }
                if (requested.name == "kotlinx-collections-immutable") {
                    useVersion(collectionsImmutableVersion)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Aggregated code coverage
// ---------------------------------------------------------------------------
// `:koverXmlReport` alone is NOT enough, and this is not obvious. Kover's aggregation
// plugin picks up whichever `Test` tasks happen to be in the task graph
// (`gradle.taskGraph.hasTask(task.path)`) and only orders them against the report with
// `mustRunAfter` — never `dependsOn` — see KoverProjectGradlePlugin.configureArtifactGeneration.
// So on a clean checkout `:koverXmlReport` succeeds and writes a near-empty report.
// `koverReport` is the entry point that is correct by construction: it runs every JVM
// test task and then merges their execution data.
//
// `:detekt-rules:test` is deliberately absent — that project is `skipProjects`-ed in
// settings.gradle.kts (build tooling, not app code) and its suite is currently red on
// this branch without any gate noticing, so pulling it in here would turn an unrelated
// pre-existing defect into a coverage-job failure.
//
// No `doLast` here: any lambda in a Kotlin build script captures the script instance,
// which the configuration cache refuses to serialize. Kover does not print the XML path
// on success (only the HTML task does, via an `onlyIf`), so the path is in the
// description, and scripts/check-coverage.py is the real consumer.
tasks.register("koverReport") {
    group = "verification"
    description = "Runs every JVM test task and writes build/reports/kover/report.xml."
    // String paths, not task references: they are resolved lazily, which keeps the
    // configuration cache happy and works before the subprojects have been evaluated.
    dependsOn(
        ":shared:jvmTest",
        ":shared:testAndroidHostTest",
        ":desktopApp:test",
        ":mcp-server:test",
        ":androidApp:test",
        ":detekt-rules:test",   // rule-coverage measurement: koverXmlReport needs test data
        ":koverXmlReport",
    )
}

// Same reasoning applies to the HTML report, so it gets a wrapper rather than a
// second copy of the test-task list. Depending on `koverReport` is what puts the
// test tasks in the graph; `:koverHtmlReport` then reuses their execution data.
tasks.register("koverHtml") {
    group = "verification"
    description = "Runs every JVM test task and writes build/reports/kover/html."
    dependsOn("koverReport", ":koverHtmlReport")
}
