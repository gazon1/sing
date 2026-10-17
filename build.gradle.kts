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
// The only way to configure kover verify rules on the root project is through
// `plugins.withId("org.jetbrains.kotlinx.kover") { configure<KoverProjectExtension> { ... } }`
// — see below. That block is evaluated DEFERRED (when the plugin is attached), which
// is why it works while the DSL block above does not.
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

// App coverage floors: INSTRUCTION 27% / BRANCH 20% / LINE 27%.
// The settings-level kover plugin applies kover to the root project but only exposes
// a partial DSL (no `reports { }`). `plugins.withId` reaches the already-applied
// per-project plugin's full DSL to configure verify rules and log output.
//
// Floors rounded down from 2026-10-15 measured values (27.6 / 20.7 / 27.4).
//
// Named constants, not bare literals, because the minimum acceptable floor is policy and
// the measured value is a fact — mixing them in one number means raising the floor looks
// like a measurement and lowering it looks like a rounding. The `require` block is the
// same contract as DETEKT_RULE_BRANCH_FLOOR's in detekt-rules/build.gradle.kts: the build
// file is the claim, and the claim is checked where it is made.
val APP_INSTRUCTION_COVERAGE_FLOOR = 27
val APP_BRANCH_COVERAGE_FLOOR = 20
val APP_LINE_COVERAGE_FLOOR = 27

require(APP_INSTRUCTION_COVERAGE_FLOOR >= 27) {
    "app instruction coverage floor must not drop below 27% — measured 27.6% on 2026-10-15"
}
require(APP_BRANCH_COVERAGE_FLOOR >= 20) {
    "app branch coverage floor must not drop below 20% — measured 20.7% on 2026-10-15"
}
require(APP_LINE_COVERAGE_FLOOR >= 27) {
    "app line coverage floor must not drop below 27% — measured 27.4% on 2026-10-15"
}

// kover 0.9.x: the project-level `kover { reports { total { xml { onCheck = true } } } }`
// DSL at lines 58-69 is silently ignored because `kover.aggregation` (settings) applies
// `org.jetbrains.kotlinx.kover` to the root via `beforeProject` AFTER the root build
// script has already been evaluated. The only way to configure verify rules on the root
// project is through `plugins.withId` (deferred) — which is this block.
//
// `minBound(45)` = verification fails if less than 45% of the aggregated counter value
// is covered across ALL JVM test tasks and ALL modules. The 45% floor is deliberately
// above the per-module floors (27/20/27) because the aggregated denominator includes
// shared/utility classes that no single feature suite exercises completely.
// Measured 57.5% on 2026-10-15; floor set well below that measured value.
plugins.withId("org.jetbrains.kotlinx.kover") {
    configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports {
            total {
                xml { onCheck = true }
                verify {
                    // Overall aggregated floor — must be declared BEFORE per-unit rules
                    // so it is evaluated in the correct order (Kover evaluates top-to-bottom
                    // and stops at the first failing rule in non-strict mode).
                    rule("overall 45% total coverage") {
                        bound {
                            minValue = 45
                        }
                    }
                    rule("instruction coverage") {
                        bound {
                            minValue = APP_INSTRUCTION_COVERAGE_FLOOR
                            coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.INSTRUCTION
                        }
                    }
                    rule("branch coverage") {
                        bound {
                            minValue = APP_BRANCH_COVERAGE_FLOOR
                            coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                        }
                    }
                    rule("line coverage") {
                        bound {
                            minValue = APP_LINE_COVERAGE_FLOOR
                            coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                        }
                    }
                }
                log {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.INSTRUCTION
                    format = "app instruction coverage: <value>%"
                }
            }
        }
    }
}
