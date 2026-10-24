import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.detekt)
    // Koin Compiler Plugin — validates classic DSL at compile time for this entry point.
    // Without this, AiToolsModule's get<Logger>() dependency on coreLoggingModule() is
    // validated only at runtime (McpServerEndToEndTest failure, fixed in #254).
    alias(libs.plugins.koin.compiler)
    application
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xskip-metadata-version-check")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

/**
 * The version this server reports in the MCP handshake.
 *
 * Read from the same property as the desktop app rather than from a literal: the
 * handshake field is what a client shows when something behaves unexpectedly, and a
 * literal there is a version nobody can check against the checkout. Two version
 * properties is how a 0.2.1 package ended up reporting 0.1.0 forever.
 */
val appVersion = (project.findProperty("desktopAppVersion") as String?) ?: "0.1.0"

application {
    mainClass.set("com.singularity.todo.mcp.MainKt")
    // The same Gradle property the desktop launcher and the Android build use, so there
    // is one version source: the release pipeline sets `desktopAppVersion`, and a client
    // that connects to the MCP server is told the same version the desktop launcher
    // prints.
    applicationDefaultJvmArgs = listOf("-Dsingularity.version=$appVersion")
}

// `applicationDefaultJvmArgs` reaches the installed distribution's start scripts; the
// `run` task is a separate JavaExec and needs the same property.
tasks.named<JavaExec>("run") {
    jvmArgs("-Dsingularity.version=$appVersion")
}

/**
 * Compose Multiplatform reaches `savedstate-compose-desktop` by two routes on the
 * runtime classpath, and Gradle refuses to install a distribution that lists the
 * same jar twice. One copy is the right outcome — two paths to one artefact is not a
 * version conflict — so `installDist` never had a chance to work before this.
 */
distributions {
    main {
        contents {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

dependencies {
    implementation(libs.kotlin.sdk)
    implementation(project(":shared"))
    implementation(libs.koog.agents)
    implementation(libs.koin.core)
    implementation(libs.kermit.koin)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.datastore.preferences.core)
    compileOnly(libs.androidx.room3.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kermit)
    implementation(libs.kotlinx.io.core.jvm)
    implementation(libs.ktor.io.jvm)

    testImplementation(libs.jvm.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("reflect"))
    // The e2e test reads the database the spawned server wrote, to see what a tool
    // call actually stored. The app opens SQLite through androidx.sqlite; a plain JDBC
    // reader needs the driver itself, and the project already ships it for desktopApp.
    testImplementation(libs.sqlite.jdbc)
    // Allure test reporting — generates JSON results for CI integration
    testImplementation(libs.allure.kotlin.junit5)

}

// ---------------------------------------------------------------------------
// detekt — the same configuration as every other module
// ---------------------------------------------------------------------------
//
// This module was on `detekt-minimal.yml` because it used to be a launcher that
// held no logic of its own. It does not any more — it is a thin launcher over the
// server in `:shared`, with one end-to-end test beside it — and a module on a
// reduced configuration is a module whose rules are a choice nobody made: the
// project's own rules, the ones written for this repository, did not reach it.
//
// So it is on the root configuration and on `:detekt-rules`, like every other
// module. Whatever that finds is fixed rather than baselined.
dependencies {
    detektPlugins(libs.detekt.formatting)   // wires ktlint into detekt so detektFormat fixes both
    detektPlugins(project(":detekt-rules"))  // PassThroughUseCaseRule — flags thin wrappers in *UseCase.kt
}

// ── The memory budget ───────────────────────────────────────────────────────
//
// The heap of a test fork is a MEASUREMENT, and the measurement lives in
// `config/docs/gradle-memory-budget.properties` — the record, one file, read by
// the build. The tables and the failed measurements behind it are in
// `docs/decisions/2026-10-22-gradle-memory-budget.md`.
//
// This module reads its OWN line of the record, so the number its test JVMs
// run with is the number the record holds for this module.
// `scripts/check-memory-budget.py` is what keeps the two in step.
val memoryBudget = Properties().apply {
    rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
}
val testTaskHeap = memoryBudget.getProperty("test.heap.module.mcp-server")
    ?: throw GradleException(
        "config/docs/gradle-memory-budget.properties declares no test.heap.module.mcp-server — " +
            "the heap of this module's test tasks has to be recorded somewhere."
    )

// JUnit Platform (Jupiter) — enables @Tag, @Nested, @ParameterizedTest, @TempDir, @AutoClose
tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // Jupiter parallel execution — see Phase 5 plan note in shared/build.gradle.kts.
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")

        val tags = (project.findProperty("test.tags") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        when {
            tags.isEmpty() -> {
                // Default: run everything EXCEPT @Tag("slow") — slow requires -Ptest.tags=slow
                excludeTags("slow")
            }
            // `-Ptest.tags=all` applies no tag filter at all. JUnit's
            // includeTags() excludes untagged tests, so passing a tag list runs
            // only the tagged minority. See the deferred-backlog entry
            // `include-tags-excludes-untagged-tests`.
            tags == listOf("all") -> Unit
            else -> includeTags(*tags.toTypedArray())
        }
    }
    // Was the default ~512 MB, then 3 GB. The heap is MEASURED, not chosen: the full
    // population dies with `OutOfMemoryError: Java heap space` at 1024 MB (with the
    // Kover agent attached), and 1536 MB carries the whole suite without one.
    // Forked test JVMs do NOT inherit org.gradle.jvmargs (that's the daemon only).
    // HeapDumpPath is module-local so parallel test runs don't overwrite each
    // other's dumps. ADR 2026-10-22-gradle-memory-budget.
    //
    // The number itself is READ, not written here: `config/docs/gradle-memory-budget.properties`
    // holds it, and the gate checks that this module's heap is the recorded one.
    maxHeapSize = testTaskHeap
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
    )
    // Allure test reporting — results written to build/allure-results/
    // See shared/build.gradle.kts for the full explanation of why workingDir is used
    // instead of the allure.results.directory system property.
    val allureDir = layout.buildDirectory.dir("allure-results").get().asFile
    workingDir = allureDir.also { it.mkdirs() }
    // Absolute path to the fat JAR, needed by McpServerEndToEndTest because
    // workingDir above is build/allure-results/ and the test would otherwise look
    // for the JAR at build/allure-results/build/libs/mcp-server.jar.
    val jarPath = layout.buildDirectory.dir("libs/mcp-server.jar").get().asFile.absolutePath
    systemProperty("mcp-server.jar.path", jarPath)
}

// Produce a fat JAR with all runtime deps merged
tasks.named<Jar>("jar") {
    archiveFileName.set("mcp-server.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.singularity.todo.mcp.MainKt"
    }
    from({
        configurations.runtimeClasspath.get().map { jar ->
            if (jar.isDirectory) files(jar) else zipTree(jar)
        }
    })
}

tasks.named("build") { dependsOn("jar") }

// distZip/distTar pack runtimeClasspath into lib/. The classpath contains the same
// Compose/Lifecycle/SavedState artifacts under BOTH androidx.* and org.jetbrains.*
// coordinates (same version, same file name, identical content) — e.g.
// androidx.compose.runtime:runtime-saveable-desktop vs org.jetbrains.compose.runtime:…
// Gradle's Zip default (FAIL) rejects that; keep the first copy, same as the fat-jar above.
tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---------------------------------------------------------------------------
// detekt — static analysis
// ---------------------------------------------------------------------------
// 2026-10-05: `ignoreFailures` was `true` here, so this task was invoked by
// ci.yml but could not fail a build — and it was absent from check.sh, so it
// never ran locally either. A gate that is wired but structurally incapable of
// failing is the same defect as a gate that is never invoked: both report a
// verdict nobody derived. It reports 0 findings, so enforcing it costs nothing
// today and starts costing something the moment it finds anything.
//
// The root configuration and the project's own rules, like every other module.
// The reduced configuration this module used to carry was a launcher module's
// exemption, and the module is not that any more.
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    ignoreFailures = false   // enforcing — a baseline covers accepted debt when there is one
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin"
    )
}

// ---------------------------------------------------------------------------
// kover — code coverage
// ---------------------------------------------------------------------------
// No `reports { }` block: coverage is reported once, from the root project
// (settings-level Kover, see settings.gradle.kts). A per-project report here
// would be a second, differently-scoped number for the same code.
