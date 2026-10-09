import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.koin.compiler)
    `maven-publish`
}

val desktopAppVersion = (project.findProperty("desktopAppVersion") as String?) ?: "0.1.0"
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
            // GenuiSurface collects with collectAsStateWithLifecycle, which needs a
            // LifecycleOwner. The app's window supplies one; a bare setContent does not, so the
            // harness has to provide its own or every surface renders nothing and every assertion
            // fails on a missing node rather than on anything the test is about.
            implementation(libs.androidx.lifecycle.runtime.compose)
            // The GenUI render harness exposes data-model values (JsonElement, JsonObject) to
            // assert what a bound field wrote; :shared declares serialization as
            // `implementation`, so it is not visible transitively.
            implementation(libs.kotlinx.serialization.json)
            // No vintage engine: every desktop test is Jupiter (`kotlin.test.Test`).
            // Under vintage, `org.junit.jupiter.api.Tag` was invisible to
            // `includeTags(...)`, so `-Ptest.tags=fast,slow` silently selected 4 of 28
            // classes — see the note on `failOnNoDiscoveredTests` above.
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter)
            implementation(libs.junit.jupiter.params)
            testImplementation(libs.kotlinx.coroutines.debug)
            // Allure test reporting — generates JSON results for CI integration
            implementation(libs.allure.kotlin.junit5)
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
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "singularity-todo"
            packageVersion = (project.findProperty("packageVersion") as String?) ?: "0.1.0"

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
    // Before every class here was tagged, `includeTags("fast","slow")` selected nothing
    // and this task still reported BUILD SUCCESSFUL. Discovering zero tests is a
    // configuration error, not a pass — see also `TestTagCoverageTest`.
    failOnNoDiscoveredTests = true

    useJUnitPlatform {
        // Desktop UI tests mount the whole production App(). The graph is built
        // per test from testPlatformModule() — FakeAppDatabase plus inert ports —
        // so no test reads or writes ~/.singularity-todo.
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
        systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")

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
        "singularity.test.steps",
        "singularity.test.a11y",
        "singularity.test.baseline",
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
    // Allure test reporting — results written to build/allure-results/
    val allureDir = layout.buildDirectory.dir("allure-results").map { it.asFile.absolutePath }
    systemProperty("allure.results.directory", allureDir)
}

dependencies {
    detektPlugins(libs.detekt.formatting)   // wires ktlint into detekt so detektFormat fixes both
    detektPlugins(project(":detekt-rules"))  // PassThroughUseCaseRule — flags thin wrappers in *UseCase.kt
    coroutinesDebugAgent(libs.kotlinx.coroutines.debug)
}

// ---------------------------------------------------------------------------
// kover — code coverage
// ---------------------------------------------------------------------------
// Configuration lives in settings.gradle.kts: one aggregated report for the whole
// build, so the flow tests here count towards the shared screens they render.
// No `kover { }` block: see settings.gradle.kts. Declaring the project plugin
// here would fail with "an extension already registered with that name" — the
// settings-level plugin applies it to every project itself.

