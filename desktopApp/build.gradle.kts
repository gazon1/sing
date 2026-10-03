import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    // Applied via id() — version catalog accessor fails for hyphenated plugin IDs.
    id("io.insert-koin.compiler.plugin") version "1.2.1"
}

val desktopAppVersion = "0.1.0"
val desktopAppVersionCode = 0

val coroutinesDebugAgent = configurations.create("coroutinesDebugAgent") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

sourceSets {
    test {
        java.srcDirs("src/jvmTest")
        dependencies {
            implementation(libs.compose.ui.test)
            implementation(libs.compose.ui.tooling.preview)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.koin.core)
            // KoinContext — the per-test KoinApplication host the desktop flow
            // tests mount the production App() inside. :shared declares koin-compose
            // as `implementation`, so it is not visible transitively here.
            implementation(libs.koin.compose)
            // Date arithmetic in the calendar flows. Same reason as koin-compose.
            implementation(libs.kotlinx.datetime)
            // ViewModel is the supertype of every VM under test; :shared declares
            // it as `implementation`, so it is not visible transitively.
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            // TestLogging installs a Kermit writer, for the same reason.
            implementation(libs.kermit)
            implementation(libs.junit4)
            implementation(libs.junit.vintage.engine)
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter)
            implementation(libs.junit.jupiter.params)
            testImplementation(libs.kotlinx.coroutines.debug)
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.koin.core)
    implementation(libs.compose.material3)
    implementation(libs.compose.material)
    implementation(libs.coil.compose)

    implementation(libs.compose.ui.tooling.preview)

    // Room for desktop database
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlite.jdbc)

    // DataStore
    implementation(libs.androidx.datastore.preferences)
}

compose.desktop {
    application {
        mainClass = "com.singularity.todo.MainKt"

        jvmArgs(
            "-Xms64m",
            "-Xmx512m",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1HeapRegionSize=8m",
            "-XX:+UseStringDeduplication",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=/tmp/singularity-oom.hprof",
            "-Dskia.cache.size=32768",
            "-Dsun.awt.disableMixing=true",
            "-XX:SoftRefLRUPolicyMSPerMB=1",
            "-Dfile.encoding=UTF-8",
            "-Dsingularity.version=$desktopAppVersion",
            "-Dsingularity.version.code=$desktopAppVersionCode",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "singularity-todo"
            packageVersion = "0.1.0"

            modules("jdk.unsupported")
            includeAllModules = false
        }
    }
}

// ---------------------------------------------------------------------------
// detekt — static analysis + ktlint (via detekt-formatting plugin)
// ---------------------------------------------------------------------------
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-desktopApp.xml")
        ignoreFailures = false              // PR 3.3: enforcing — baseline covers accepted debt
    source.setFrom("src/main/kotlin", "src/jvmTest/kotlin")
}

// JUnit Platform (Jupiter) — enables @Tag, @Nested, @ParameterizedTest, @TempDir, @AutoClose
tasks.withType<Test>().configureEach {
        useJUnitPlatform {
        // Desktop UI tests mount the whole production App(). The graph is built
        // per test from testPlatformModule() — FakeAppDatabase plus inert ports —
        // so no test reads or writes ~/.singularity-todo.
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
        systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")

        val tags = (project.findProperty("test.tags") as String?)
            ?.split(",")?.orEmpty() ?: emptyList()
        if (tags.isNotEmpty()) {
            includeTags(*tags.toTypedArray())
        } else {
            // Default: run everything EXCEPT @Tag("slow") — slow requires -Ptest.tags=slow
            excludeTags("slow")
        }
    }
    // forkEvery = 1 ensures each test class runs in its own JVM process. This
    // guarantees Kermit ring-buffer logs and FailureBundle directories cannot be
    // corrupted by concurrent tests in other classes (parallel modes are both
    // same_thread so this is a safety net against any future parallel changes).
    // Performance gate: if suite duration grows > 25%, degrade to methods-only
    // (keep mode.default=same_thread, restore classes to concurrent) and document
    // the remaining cross-class Kermit mixing as a known limitation.
    forkEvery = 1
    maxParallelForks = 2

    // Forward the opt-in test switches from the Gradle CLI into the forked test JVM.
    // A `-D` on the Gradle command line configures the daemon, not the test
    // process, so opt-in test switches would otherwise be silently ignored —
    // the flag parses fine and simply has no effect, which is worse than a
    // hard failure.
    //
    // These MUST go through `providers.systemProperty(...)`, not
    // `System.getProperty(...)`: the configuration cache snapshots plain
    // System.getProperty reads at configuration time, so a CLI flag added later
    // silently never reached the test JVM while the cached configuration was
    // reused. Provider reads are tracked as configuration inputs — changing the
    // flag invalidates the cache and the new value lands in the fork.
    listOf(
        "singularity.test.log",
        "singularity.test.screenshot",
        "singularity.ui.dumpTree",
        "retry.maxAttempts",
        "retry.failOnPassedAfterRetry",
    ).forEach { key ->
        systemProperty(key, providers.systemProperty(key).orElse("").get())
    }

    // Bumped from default ~512 MB to 3 GB. Forked test JVMs do NOT inherit
    // org.gradle.jvmargs (that's the daemon only). HeapDumpPath is module-local so
    // parallel test runs don't overwrite each other's dumps.
    maxHeapSize = "3g"
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
    )
    // -javaagent for kotlinx-coroutines-debug: required for JDK 21+ compatibility;
    // DebugProbes.install() emits a dynamic-loading warning on JDK 21 and fails on JDK 22+.
    // Resolved eagerly as a plain String (not via CommandLineArgumentProvider) to avoid
    // capturing the Gradle script object, which breaks the configuration cache.
    val coroutinesDebugAgentPath: String = configurations
        .named("coroutinesDebugAgent").get()
        .resolve()
        .single { it.name.contains("debug") && it.name.endsWith(".jar") }
        .absolutePath
    jvmArgs("-javaagent:$coroutinesDebugAgentPath")
}

dependencies {
    detektPlugins(libs.detekt.formatting)   // wires ktlint into detekt so detektFormat fixes both
    detektPlugins(project(":detekt-rules"))  // PassThroughUseCaseRule — flags thin wrappers in *UseCase.kt
    coroutinesDebugAgent(libs.kotlinx.coroutines.debug)
}

// ---------------------------------------------------------------------------
// kover — code coverage
// ---------------------------------------------------------------------------
kover {
    reports {
        total {
            html { onCheck = true }
            xml { onCheck = true }
        }
    }
}

