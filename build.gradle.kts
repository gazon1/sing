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
    // applied to every project from there. Its version is kept in libs.versions.toml.
    // The project-level `kover { reports { total { ... } } }` API (needed for verify rules)
    // is available here because `kover.aggregation` auto-applies `org.jetbrains.kotlinx.kover`
    // to every project INCLUDING the root via beforeProject — so the plugin is already
    // on the classpath and the ID resolves; Gradle deduplicates it silently.
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
// koverVerify is registered by the settings-level `kover.aggregation` plugin on every
// project. The settings-level `kover { }` block (settings.gradle.kts) only handles
// filters and the `enableCoverage()` flag — it does NOT expose `reports { total { } }`.
//
// The project-level `kover { reports { total { xml { } } verify { } } }` DSL is
// also NOT available on the root project at root-build-script evaluation time:
// `kover.aggregation` applies `org.jetbrains.kotlinx.kover` to the root via
// `beforeProject` AFTER the root build script has already been evaluated.
// Symptom of trying to use it: every reference inside the `kover { }` block
// (`reports`, `total`, `xml`, `verify`, `rule`, `minBound`) is "Unresolved".
//
// The only way to configure kover verify rules on the root project WAS through
// `plugins.withId("org.jetbrains.kotlinx.kover") { configure<KoverProjectExtension> { ... } }`
// — a deferred block, evaluated when the plugin is attached, which is why it
// worked while the DSL block above did not. That block is GONE: it is where the
// app's coverage floors used to live, and the floors it declared were never
// evaluated by anything, so the rule was a number in a file that no task read.
// The floors now live in `settings.gradle.kts`, in the aggregation plugin's own
// settings-level block, which is where the plugin reads them. See
// docs/decisions/2026-10-22-the-app-coverage-floor-lives-in-the-settings.md.
//
// The root build script still owns the REPORT: `koverReport` is the task that
// runs every JVM test task and merges their execution data, and `:koverVerify`
// depends on it, so the verification reads the same report the run produced.
//
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
// description, and `:koverVerify` is the real consumer.
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

// `:koverVerify` on its own measures NOTHING.
//
// The aggregation plugin merges the execution data of whichever `Test` tasks happen
// to be in the task graph, and it never depends on them — so a standalone
// `:koverVerify` verifies an empty report. Measured on 2026-10-22:
//
//   Rule 'app instruction coverage' violated: instructions covered percentage is
//   0.000000, but expected minimum is 27
//
// That was invisible while the app floors did not exist: the rule set was empty, and
// an empty rule set is satisfied by an empty measurement. With the floors declared in
// settings.gradle.kts it is a permanent red, in CI and locally alike.
//
// One line makes the task correct by construction: it pulls in the same entry point
// that runs the tests and writes the report. The test tasks are then up to date in
// every caller that has already run them, so this costs nothing there.
//
// `matching { }.configureEach { }` rather than `named(...)`: the aggregation plugin
// registers the task, and this configuration must not depend on whether that happened
// before or after this script was evaluated.
tasks.matching { it.name == "koverVerify" }.configureEach {
    dependsOn("koverReport")
}

// Same reasoning applies to the HTML report, so it gets a wrapper rather than a
// second copy of the test-task list. Depending on `koverReport` is what puts the
// test tasks in the graph; `:koverHtmlReport` then reuses their execution data.
tasks.register("koverHtml") {
    group = "verification"
    description = "Runs every JVM test task and writes build/reports/kover/html."
    dependsOn("koverReport", ":koverHtmlReport")
}

// Markdown table from the Kover XML report. Depends on koverReport so the XML
// is guaranteed to exist when this task runs. Parses the XML natively using
// javax.xml (available in the Gradle JVM) — no python3 dependency.
//
// The report path arrives as a task *input*, not as `project.rootProject.layout`
// read inside the action. The configuration cache forbids reaching for `project`
// at execution time, and this task did exactly that — so it has failed on every
// invocation since configuration-cache hardening (2026-10-16, CI run 38942680186
// among others). `ci.yml` marks the step `|| true`, so the failure was invisible
// and the coverage table has been missing from the job summary on every run while
// the workflow claimed to report one. The local recipe `just coverage-markdown`
// was broken the same way.
//
// Input over the *directory* rather than the file, deliberately: `@InputFile`
// would fail Gradle's own validation with "file does not exist" whenever the XML
// is absent, which replaces a message that names the fix (":koverReport first")
// with one that does not.
abstract class KoverMarkdownTableTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reportsDir: ConfigurableFileCollection

    @TaskAction
    fun generate() {
        val xmlFile = reportsDir.singleFile.resolve("report.xml")

        if (!xmlFile.exists()) {
            throw GradleException(
                "kover-markdown-table: ${xmlFile.absolutePath} not found. " +
                "Run ':koverReport' first."
            )
        }

        val rows = mutableListOf<ModuleCoverage>()
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xmlFile)

        // Kover may emit <project name="..."> elements. The <counter> elements
        // appear nested inside them. getElementsByTagName finds elements at ANY depth,
        // so we must first check whether <project> elements exist at all; if they do,
        // we read counters per-project rather than globally.
        val projectEls = doc.getElementsByTagName("project")
        if (projectEls.length > 0) {
            for (i in 0 until projectEls.length) {
                val projectEl = projectEls.item(i) as org.w3c.dom.Element
                val name = projectEl.getAttribute("name").ifBlank { "?" }
                val mod = ModuleCoverage(name)
                val counters = projectEl.getElementsByTagName("counter")
                for (j in 0 until counters.length) {
                    updateModuleFromCounter(mod, counters.item(j) as org.w3c.dom.Element)
                }
                rows.add(mod)
            }
        } else {
            // No <project> wrappers: scan all counters in document order and keep the
            // last value of each type — mirrors the Python script's behaviour.
            val total = ModuleCoverage("total")
            val allCounters = doc.getElementsByTagName("counter")
            for (i in 0 until allCounters.length) {
                updateModuleFromCounter(total, allCounters.item(i) as org.w3c.dom.Element)
            }
            rows.add(total)
        }

        printTable(rows)
    }

    private fun updateModuleFromCounter(mod: ModuleCoverage, el: org.w3c.dom.Element) {
        val type = el.getAttribute("type")
        if (type !in listOf("LINE", "BRANCH", "INSTRUCTION")) return
        val missed  = el.getAttribute("missed").toIntOrNull()  ?: 0
        val covered = el.getAttribute("covered").toIntOrNull() ?: 0
        when (type) {
            "LINE"        -> { mod.lineMissed   = missed;  mod.lineCovered   = covered }
            "BRANCH"      -> { mod.branchMissed = missed;  mod.branchCovered = covered }
            "INSTRUCTION" -> { mod.instrMissed  = missed;  mod.instrCovered  = covered }
        }
    }

    private fun printTable(rows: List<ModuleCoverage>) {
        println("| Module | LINE (covered/total) | BRANCH (covered/total) | INSTRUCTION (covered/total) |")
        println("|---|---|---|---|")
        for (r in rows) {
            println("| ${r.name} | ${cell(r.lineCovered,   r.lineMissed)} " +
                    "| ${cell(r.branchCovered, r.branchMissed)} " +
                    "| ${cell(r.instrCovered,  r.instrMissed)} |")
        }
    }

    private fun cell(covered: Int, missed: Int): String {
        val total = covered + missed
        if (total == 0) return "—"
        return "$covered/$total (${String.format("%.1f", covered * 100.0 / total)}%)"
    }
}

private data class ModuleCoverage(val name: String) {
    var instrCovered = 0; var instrMissed = 0
    var lineCovered  = 0; var lineMissed  = 0
    var branchCovered = 0; var branchMissed = 0
}

tasks.register<KoverMarkdownTableTask>("koverMarkdownTable") {
    group = "verification"
    description = "Parses build/reports/kover/report.xml and prints a markdown coverage table to stdout."
    // Configuration time, not action time — the same boundary the input exists to
    // enforce. `layout` here is the project's own, resolved while the configuration
    // cache is being written.
    reportsDir.from(layout.buildDirectory.dir("reports/kover"))
    dependsOn("koverReport")
}

// App coverage floors: INSTRUCTION 27% / BRANCH 20% / LINE 27% for the full
// population, and a second set for the fast population.
//
// They live in settings.gradle.kts, and that is not a matter of taste. The
// aggregation plugin registers `:koverXmlReport`, `:koverHtmlReport` and
// `:koverVerify` on the root project and reads its rules from the
// settings-level `kover { reports { verify { } } }` — the root project has no
// Kover extension of its own to configure. The `plugins.withId("org.jetbrains.
// kover") { configure { … } }` block that used to declare them HERE never ran
// at all: probed on 2026-10-22 with a `println` inside it, and the line never
// appeared. So the floors this file declared were never evaluated by anything,
// and the CI step that reads them ("Coverage floors") was green no matter what
// the coverage was.
//
// The floors are also per population now — a fast run measures less coverage
// than a full one — and the population is read from the same `-Ptest.tags`
// property the tests use. See ADR 2026-10-22-ci-fast-only-pr-full-on-schedule.
//
// What stays here is the report READING: the markdown table below renders
// build/reports/kover/report.xml, which is the aggregate the floors above are
// checked against.
