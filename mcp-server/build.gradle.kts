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

application {
    mainClass.set("com.singularity.todo.mcp.MainKt")
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
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("reflect"))
    // Allure test reporting — generates JSON results for CI integration
    testImplementation(libs.allure.kotlin.junit5)

    // detektPlugins(libs.detekt.formatting) — removed: mcp-server uses detekt-minimal.yml
    // which does not include ktlint config. Formatting is handled by shared + desktopApp.
}

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
    // Bumped from default ~512 MB to 3 GB. Forked test JVMs do NOT inherit
    // org.gradle.jvmargs (that's the daemon only). HeapDumpPath is module-local so
    // parallel test runs don't overwrite each other's dumps.
    maxHeapSize = "3g"
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
    )
    // Allure test reporting — results written to build/allure-results/
    val allureDir = layout.buildDirectory.dir("allure-results").map { it.asFile.absolutePath }
    systemProperty("allure.results.directory", allureDir)
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
// Deliberately still on detekt-minimal.yml rather than the full detekt.yml:
// this module does not apply the :detekt-rules plugin, so the custom rule sets
// are not on its classpath. Bringing it onto the full config is a decision, not
// a mechanical fix, and is not taken here. What is taken here is that the
// rules that *do* apply can now stop a build.
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt-minimal.yml"))
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
