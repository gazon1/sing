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

// `desktopAppVersion` and `packageVersion` are different properties on purpose, and
// until 2026-10-16 the release pipeline set only the second. The package then declared
// 0.2.1 while every jar inside it reported 0.1.0 through `-Dsingularity.version`, which
// is what `singularity-todo --version` prints and what the in-app update gate compares
// against the releases feed. A user who installed the 0.2.1 package was told 0.1.0
// forever. The install-and-smoke step in release.yml now runs the installed launcher
// and asserts this value, which is the only check that could have caught it.
val desktopAppVersionCode =
    (project.findProperty("desktopAppVersionCode") as String?)?.toIntOrNull() ?: 0

val coroutinesDebugAgent = configurations.create("coroutinesDebugAgent") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

kotlin {
    // The app runs on 21 — the JDK gradle-daemon-jvm.properties pins the daemon
    // to, and the only one desktop flows are verified on. Toolchaining here
    // makes that independent of whichever JDK happens to launch Gradle (the
    // launcher on this host is 25), so compile, test forks and `run` all use 21
    // without relying on daemon inheritance (#413).
    jvmToolchain(21)
}

sourceSets {
    test {
        java.srcDirs("src/jvmTest")
        dependencies {
            implementation(libs.compose.ui.test)
            implementation(libs.compose.ui.tooling.preview)
            // NavDisplay — the nested-entry ViewModel scope test renders one.
            // :shared declares nav3 as `implementation`, so it is not visible
            // transitively here.
            implementation(libs.androidx.navigation3.ui.desktop)
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
            // The toolbox-wiring test resolves the real Koin tool list, whose type
            // is Koog's. Without it the test cannot name what it is checking.
            testImplementation(libs.koog.agents)
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

    // Navigation 3 — the deep-link request type lives in the runtime, and the
    // entry point turns the `--deeplink=` argument into one. `:shared` declares
    // nav3 as `implementation`, so it is not visible transitively.
    implementation(libs.androidx.navigation3.runtime)
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
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "singularity-todo"
            packageVersion = (project.findProperty("packageVersion") as String?) ?: "0.1.0"

            modules("jdk.unsupported")
            includeAllModules = false
        }
    }
}

// ---------------------------------------------------------------------------
// D1 (#686): Patch jpackage's postinst to be a no-op.
//
// jpackage generates a postinst that calls `xdg-desktop-menu install`, which exits 3
// on a headless Debian system because neither /usr/share/desktop-directories nor
// /usr/share/applications is writable and XDG_DATA_DIRS="" prevents the fallback.
// The menu entries the package installs are unused on a headless install.
//
// `linuxConfig { postinstScript = … }` does not exist in compose-desktop 1.12.0,
// so we patch the .deb after packageDeb creates it using standard Debian tools.
// ---------------------------------------------------------------------------

val patchDebPostinst = tasks.register<DefaultTask>("patchDebPostinst") {
    // Run after packageDeb. Not all formats produce a .deb, so the glob inside
    // is the only part that can tolerate being a no-op when no .deb exists.
    // packageDeb is created lazily by the compose-desktop plugin, so we access it
    // by name string rather than a typed reference.
    dependsOn("packageDeb")

    doLast {
        val buildDir = layout.buildDirectory.asFile.get()
        val debDir = buildDir.resolve("compose/binaries/main/deb")
        val debFiles = debDir.listFiles { _, name -> name.endsWith(".deb") }
            ?: error("No .deb files found in $debDir — packageDeb must run first")

        for (debFile in debFiles) {
            val tmpDir = buildDir.resolve("tmp-deb-patch/${debFile.nameWithoutExtension}")
            try {
                // Use ProcessBuilder directly — avoids any Gradle DSL scope issues inside doLast.
                val run = { cmd: List<String> ->
                    val pb = ProcessBuilder(cmd)
                    pb.redirectErrorStream(true)
                    val result = pb.start().waitFor()
                    if (result != 0) error("Command failed: $cmd  (exit $result)")
                }

                // Extract the package data and control files.
                run(listOf("dpkg-deb", "-x", debFile.absolutePath, tmpDir.absolutePath))
                run(listOf("dpkg-deb", "-e", debFile.absolutePath, "$tmpDir/DEBIAN"))

                val postinst = tmpDir.resolve("DEBIAN/postinst")
                if (postinst.exists()) {
                    // Replace with a no-op that handles all standard dpkg trigger arguments.
                    // jpackage's original postinst called xdg-desktop-menu, which aborts on
                    // headless Debian (no desktop-directories). This one just exits 0 (#686).
                    postinst.writeText(
                        """#!/bin/bash
case "$1" in
    configure|abort-upgrade|abort-remove|abort-deconfigure)
        exit 0
        ;;
esac
"""
                    )
                    postinst.setExecutable(true)
                }

                // Repack the .deb in-place, replacing the original.
                run(listOf("dpkg-deb", "-b", tmpDir.absolutePath, debFile.absolutePath))
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    }
}

// patchDebPostinst dependsOn "packageDeb" directly, so running
// `packageDeb` automatically runs the patch too.



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

    // The test JVM is headless BY CONSTRUCTION, not by accident of the host.
    //
    // AppVersionGateScreenTest calls openStoreOnDesktop(), which reaches
    // Desktop.getDesktop().browse(...). On a host where a display is reachable
    // (DISPLAY=:0 here) that launches a REAL browser: Firefox starts, crashes
    // with a minidump, and the Gradle test worker hangs forever — two full
    // check.sh runs were lost to it, each sitting in :desktopApp:test for 30+
    // minutes with no progress. The test is written to absorb HeadlessException,
    // which is what a headless JVM does, so the headless path is the intended
    // one; the hang was the environment leaking into the test.
    //
    // java.awt.headless=true makes Desktop.getDesktop() throw HeadlessException
    // directly, so the test exercises the branch it was written for. This is a
    // system property of the forked test JVM only — the `:desktopApp:run`
    // application task is not a Test task and is unaffected (it still needs
    // xvfb-run -a for its own window).
    systemProperty("java.awt.headless", "true")

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

    // mergeReruns=true: retry attempts are merged into one <testcase> element with a
    // <flakyFailure> child. This prevents check-test-runs.py from double-counting retries
    // and ensures the annotation script sees one entry per test method.
    reports.junitXml.mergeReruns.set(true)

    // Bumped from default ~512 MB to 3 GB. Forked test JVMs do NOT inherit
    // org.gradle.jvmargs (that's the daemon only). HeapDumpPath is module-local so
    // parallel test runs don't overwrite each other's dumps.
    maxHeapSize = "3g"
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
        // #413/#414 stack-depth probe: the startup StackOverflowError was
        // attributed to deep Compose + Koin entry composition. 4 MB rules depth
        // out as the cause — a recurrence under this flag falsifies the
        // stack-depth hypothesis instead of silently "fixing" it.
        "-Xss4m",
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
    // (#694) Eagerly resolved absolute path so the system property is a plain String,
    // not a Provider — systemProperty does not call `.get()` on a Provider the way
    // workingDir does, so lazy resolution silently did nothing before.
    val allureDir = layout.buildDirectory.dir("allure-results").get().asFile.absoluteFile
    systemProperty("allure.results.directory", allureDir.also { it.mkdirs() }.absolutePath)
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

