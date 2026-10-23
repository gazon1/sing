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

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import java.io.File

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
// The test population, and the manifest of every run
// ---------------------------------------------------------------------------
//
// A POPULATION is a name: `-Ptest.population=fast` or `-Ptest.population=full`.
// The tags each name selects live in `config/test-populations.properties` —
// one file, read here, in settings.gradle.kts (which picks the coverage floors
// by population) and by the Python gates (which compare what a job's
// invocations asked for). Before this file existed the selection was the
// literal string "fast,slow", written in the workflow, in settings.gradle.kts
// and in a gate, and nothing said which of them was the definition.
//
// The name is turned into the tag list ONCE, here, and handed to every module
// as the `test.tags` property the modules already read. A module's build script
// does not know about populations: it reads a tag list, and this block is what
// puts the list there.
//
// `-Ptest.tags` still works and still wins: it is the raw knob, for a local
// run that wants a selection no population names. What does NOT work is
// passing both — two spellings of one selection is how they drift apart.
subprojects {
    val populations = java.util.Properties().apply {
        rootProject.file("config/test-populations.properties").inputStream().use { load(it) }
    }
    val population = providers.gradleProperty("test.population").orNull
    val explicitTags = providers.gradleProperty("test.tags").orNull

    if (population != null && explicitTags != null) {
        throw GradleException(
            "-Ptest.population=$population and -Ptest.tags=$explicitTags were both given. " +
                "One selection, one spelling: name a population, or spell the tags."
        )
    }
    if (population != null) {
        val tags = populations.getProperty(population)
        if (tags == null) {
            throw GradleException(
                "unknown test population '$population' — the known ones are: " +
                    populations.stringPropertyNames().sorted().joinToString(", ") +
                    " (config/test-populations.properties)"
            )
        }
        // The modules read `project.findProperty("test.tags")`. Handing them the
        // resolved list here is what lets six build scripts stay free of the
        // population vocabulary.
        extra["test.tags"] = tags
    }
    extra["test.population"] = population ?: ""

    // ── The memory budget ───────────────────────────────────────────────────
    //
    // One record: `config/docs/gradle-memory-budget.properties`. The heap of a
    // test fork and the heap of the Gradle daemon were literal strings in five
    // build scripts and in gradle.properties, and the measurement that
    // produced them lived in an ADR that no build read and no gate checked.
    //
    // The modules that name a budget of their own read their OWN key from the
    // record (`test.heap.module.<project>`), so the number a module runs with is
    // the number the record holds for that module. The default below is what a
    // module that names no budget of its own gets — the two small toolchain
    // modules, which declare no `maxHeapSize` at all.
    //
    // The measurement is in `docs/decisions/2026-10-22-gradle-memory-budget.md`;
    // the gate is `scripts/check-memory-budget.py`, which checks that every
    // module's heap is the recorded one and that gradle.properties carries the
    // recorded daemon heap.
    val memoryBudget = java.util.Properties().apply {
        rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
    }
    val defaultTestHeap = memoryBudget.getProperty("test.heap.default")
        ?: throw GradleException(
            "config/docs/gradle-memory-budget.properties declares no test.heap.default — " +
                "the heap of a test task has to be recorded somewhere."
        )
    // The default is applied to EVERY test task in the build. A module that
    // names a budget of its own overrides it, because its own configuration
    // runs after this one.
    tasks.withType<Test>().configureEach {
        maxHeapSize = defaultTestHeap
    }

    // ── The run manifest ────────────────────────────────────────────────────
    //
    // Gradle leaves no record of whether a run was filtered, so a run that
    // selected one class rewrites the XML directory with that class's results,
    // and every gate that reads it — `check-test-runs.py`,
    // `check-flaky-tests.py` — reports a verdict it cannot source. The gates
    // were right and useless: they said "a suite stopped running" about a tree
    // where nothing had.
    //
    // The manifest is written here, for EVERY module, because a convention that
    // lives in one module is a convention the other modules do not have. It used
    // to be written by `shared/build.gradle.kts` alone, and the gate that asks
    // "were the slow classes supposed to run here?" could only answer for
    // `shared` — for `desktopApp` (where 49 of 62 classes are slow) the answer
    // was a fallback that cannot tell "selected no slow class" from
    // "deliberately fast-only". That distinction is the whole question.
    //
    // One property decides "partial", and it is the one Gradle already tracks:
    // a `--tests` filter populates `filter.includePatterns`, and nothing else
    // does. A `-Ptest.population` filter deliberately does NOT mark the run
    // partial — population selection is what CI does on purpose, and the floors
    // are recorded against it. The population IS recorded, though: it is what
    // this run measured, and the gate needs it to answer the slow-class
    // question.
    tasks.withType<Test>().configureEach {
        val runDir = layout.buildDirectory.dir("test-results/$name")
        val selectedPopulation = (project.findProperty("test.population") as String?) ?: ""
        val selectedTags = (project.findProperty("test.tags") as String?) ?: ""
        doLast {
            val filters = filter.includePatterns
            val partial = filters.isNotEmpty()
            val executed = runDir.get().asFile.listFiles()
                ?.count { it.name.startsWith("TEST-") && it.name.endsWith(".xml") } ?: 0
            runDir.get().asFile.resolve("run-manifest.properties").writeText(
                """
                # Written by the root build script. Read by scripts/check-test-runs.py.
                task=$name
                partial=$partial
                filters=${filters.joinToString(",")}
                population=$selectedPopulation
                tags=$selectedTags
                executedClasses=$executed
                finishedAt=${System.currentTimeMillis()}
                """.trimIndent() + "\n",
            )
            logger.lifecycle(
                "$name run manifest: ${if (partial) "PARTIAL" else "full"} " +
                    "($executed classes${if (partial) ", filters=${filters.joinToString(",")}" else ""}" +
                    "${if (selectedPopulation.isNotEmpty()) ", population=$selectedPopulation" else ""}" +
                    "${if (selectedTags.isNotEmpty()) ", tags=$selectedTags" else ""})",
            )
        }
    }
}

// detektChanged — the scoped lint: the same detekt, over the changed files
// ---------------------------------------------------------------------------
// WHY
// ---
// `:shared:detekt` analyses the whole module: three to four minutes, of which
// the file the author just wrote is one part. A mechanical finding — a
// too-complex method, a long method, a missing blank line between `when`
// branches — is a finding about the file that was just written, so the
// whole-module run spends minutes to answer a question about one file.
//
// `detektChanged` is that run, scoped: the same config, the same baseline, the
// same rules, the same plugin classpath. Only the input is the set of changed
// files, so a finding here is the finding CI reports.
//
// WHERE THE FILE LIST COMES FROM
// ------------------------------
// `scripts/lint-changed.py` computes it from git — the merge base with the base
// branch plus the working tree, so a staged-but-uncommitted change counts — and
// hands it over as a Gradle property:
//
//   -PdetektChangedFilesFile=<path>   a file with one repo-relative path per line
//   -PdetektChangedFiles=a,b,c         the paths themselves, comma-separated
//
// Two properties, not one: a single property cannot be both "the path of a
// list" and "the list" without guessing, and the guess would read a Kotlin
// file as a list of paths.
//
// No property, no files, no task: `source` is empty and the task is skipped
// (NO-SOURCE). "Nothing changed here" is not a lint failure.
//
// WHY THE LIST IS READ AT CONFIGURATION TIME
// ------------------------------------------
// A provider would be evaluated at execution time, and the configuration cache
// refuses to serialize a lambda that captures the build script — the reason the
// kover tasks below have no `doLast`. A Gradle property is a plain string:
// there is nothing to serialize, and the caller decides the file list before
// Gradle starts.
//
// WHY `afterEvaluate`
// -------------------
// A module's `detekt { }` block says which directories that module's detekt
// scans, and it runs after the `plugins { }` block. At the moment the plugin is
// attached the extension does not know its source directories yet, and reading
// them then would silently scan the wrong set. `afterEvaluate` runs after the
// whole build script, when the extension is fully configured.
//
// WHY THE ROOT BUILD FILE
// -----------------------
// The detekt plugin's classes are needed to declare the task, and they are on
// the buildscript classpath of the root project (`alias(libs.plugins.detekt)
// apply false` above) but not on the classpath a script plugin applied from a
// module's build file would compile against.
//
// The task type is the same class the module's own detekt task uses: the
// subprojects' buildscript classloaders delegate to the root's, so the plugin's
// own `tasks.withType(Detekt::class.java)` conventions — config, rule set,
// plugin classpath, `buildUponDefaultConfig` — apply to this task too.
val changedFilesFileProperty: String = providers.gradleProperty("detektChangedFilesFile").orNull ?: ""
val changedFilesProperty: String = providers.gradleProperty("detektChangedFiles").orNull ?: ""

val changedPaths: List<String> = (
    if (changedFilesFileProperty.isNotEmpty()) {
        project.file(changedFilesFileProperty).readLines()
    } else {
        changedFilesProperty.split(',')
    }
    )
    .map { it.trim() }
    .filter { it.isNotEmpty() }

// The changed Kotlin files of the repository. The file types are the ones detekt
// includes by default (`*.kt`, `*.kts`); anything else is not a file CI would
// analyse either.
val changedKotlinFiles: List<File> = changedPaths
    .map { project.file(it).canonicalFile }
    .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".kts")) }

subprojects {
    plugins.withId("dev.detekt") {
        afterEvaluate {
            val extension = extensions.getByType(DetektExtension::class.java)

            // The files of THIS module that THIS module's detekt actually scans:
            // the same source directories the module's own detekt task scans. A
            // file outside them is not analysed by the whole-module run either,
            // so analysing it here would report a finding CI never reports.
            val moduleSourceDirs: List<File> = extension.source
                .filter { it.exists() }
                .files
                .map { it.canonicalFile }

            val moduleFiles: List<File> = changedKotlinFiles.filter { file ->
                moduleSourceDirs.any { dir -> file.toPath().startsWith(dir.toPath()) }
            }

            tasks.register<Detekt>("detektChanged") {
                group = "verification"
                description = "Run detekt over the changed Kotlin files of ${project.path} only"
                // Same baseline as the module's own detekt task: a finding the
                // baseline already carries is carried here too, or the scoped run
                // would report debt the whole-module run accepts.
                baseline.convention(extension.baseline)
                // Config, rule set, plugin classpath and `buildUponDefaultConfig`
                // are conventions the detekt plugin sets from the module's own
                // `detekt { }` block — the scoped task inherits them rather than
                // restating them.
                // `setSource`, not `source.setFrom`: the Detekt task overrides
                // `getSource()`, so Kotlin sees a read-only property there.
                setSource(moduleFiles)
            }
        }
    }
}

// Test tasks are never served from the build cache
// ---------------------------------------------------------------------------
//
// The build cache is what makes the JVM job reusable: compiling `shared`,
// `desktopApp` and `androidApp` is the same work on every run of the same
// commit, and re-doing it is a large share of the job's half hour. A cache
// entry for a compilation task is exactly what the cache is for.
//
// A test task is a different thing. Served from the cache, it did not run — and
// the gate that measures how many tests ran (`scripts/check-test-runs.py`) has to
// be able to tell a real run from a restored one. A restored run is not evidence
// about today; it is evidence about the last run of the same commit, on a
// machine nobody is watching.
//
// So the exclusion lives here, in the build, rather than in the CI step. The step
// used to pass `--no-build-cache`, which excluded *everything* — the compilation
// included — while the comment next to it claimed the cache stayed "warm for
// configuration and compilation tasks". A flag is a property of the invocation;
// this is a property of the task, and it holds in every invocation: CI, the
// pre-push hook, and a local run.
allprojects {
    tasks.withType<Test>().configureEach {
        outputs.cacheIf { false }
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
// `:adr-core:jvmTest` was missing here, and that was a measurement gap rather than a
// style question: `:adr-core` holds the app's ADR rules (`com.singularity.todo.feature.ai.tools`),
// so its classes ARE in the coverage denominator — but nothing ran its tests in the
// coverage job, and the report scored them by whatever `:shared`'s suite happened to
// touch. Its tests are green (`:adr-core:jvmTest`, 8 s) and now they run. The
// denominator does not change — the classes were already in it — so the measured
// coverage can only rise.
//
// `:adr-cli` is absent for the opposite reason: it is build tooling, excluded from
// the coverage scope in settings.gradle.kts.
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
        ":adr-core:jvmTest",    // app code in the denominator (feature.ai.tools): its own tests measure it
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

    // The estimate, printed before the run.
    //
    // The instrumented coverage run costs ~37 minutes (the Kover agent
    // multiplies the cost of every test; the uninstrumented `:shared:jvmTest`
    // takes 6 min 44 s). That number decides whether a timeout is adequate,
    // and it was nowhere: a 15-minute timeout on a 37-minute task kills the
    // run after a quarter of an hour and reports a failure that says nothing
    // about the code.
    //
    // The estimate is read from `config/docs/coverage-cost.properties` — the
    // same file `docs/testing/gate-costs.md` publishes, so the number the
    // build prints and the number the reader reads cannot drift apart.
    val coverageCostFile = rootProject.file("config/docs/coverage-cost.properties")
    // Configuration time, not action time: the configuration cache forbids
    // reaching for `project` at execution time, and the population is a
    // property of the invocation, not of the run.
    val coveragePopulation = (project.findProperty("test.population") as String?)?.takeIf { it.isNotEmpty() }
        ?: "fast"
    doFirst {
        val costs = java.util.Properties().apply {
            if (coverageCostFile.isFile) {
                coverageCostFile.inputStream().use { load(it) }
            }
        }
        val population = coveragePopulation
        val millis = costs.getProperty("$population.ms")
        val measuredAt = costs.getProperty("measuredAt") ?: "an unrecorded date"
        if (millis == null) {
            logger.lifecycle(
                "koverVerify: no recorded cost for the '$population' population " +
                    "(${coverageCostFile.name}) — this run is instrumented and takes " +
                    "tens of minutes."
            )
        } else {
            val minutes = millis.toLong() / 60_000
            logger.lifecycle(
                "koverVerify: the instrumented coverage run of the '$population' population " +
                    "takes about $minutes min (measured $measuredAt; " +
                    "${costs.getProperty("machine") ?: "an unnamed machine"}) — " +
                    "see docs/testing/gate-costs.md"
            )
        }
    }
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
        if (type !in listOf("LINE", "BRANCH", "INSTRUCTION", "CLASS")) return
        val missed  = el.getAttribute("missed").toIntOrNull()  ?: 0
        val covered = el.getAttribute("covered").toIntOrNull() ?: 0
        when (type) {
            "LINE"        -> { mod.lineMissed   = missed;  mod.lineCovered   = covered }
            "BRANCH"      -> { mod.branchMissed = missed;  mod.branchCovered = covered }
            "INSTRUCTION" -> { mod.instrMissed  = missed;  mod.instrCovered  = covered }
            "CLASS"       -> { mod.classMissed  = missed;  mod.classCovered  = covered }
        }
    }

    private fun printTable(rows: List<ModuleCoverage>) {
        // The CLASS column is the one that says WHICH report this is. The
        // percentages alone cannot tell a full report from a partial one: the
        // fast population's report held 4735 classes over 579,002 instructions
        // on 2026-10-23, while the full population's report of 2026-10-22 held
        // 4642 classes over 565,144 — the fast report is not the smaller of
        // the two universes, it is the same one on a tree that grew. A reader
        // who compares "28.7%" with a recorded floor and reads it as a fifth
        // of the codebase has misread the report — and then measures something
        // that was never broken.
        //
        // (The number published before this column existed — 2.82M
        // instructions — was the sum of every INSTRUCTION counter in the XML,
        // so five times the report's own denominator. The percentages were
        // unaffected: the ratio is the same at every level.)
        println("| Module | CLASS (covered/total) | LINE (covered/total) | BRANCH (covered/total) | INSTRUCTION (covered/total) |")
        println("|---|---|---|---|---|")
        for (r in rows) {
            println("| ${r.name} | ${cell(r.classCovered,  r.classMissed)} " +
                    "| ${cell(r.lineCovered,   r.lineMissed)} " +
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
    var classCovered = 0; var classMissed = 0
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
