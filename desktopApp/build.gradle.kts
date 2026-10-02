import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

val desktopAppVersion = "0.1.0"
val desktopAppVersionCode = 0

sourceSets {
    test {
        java.srcDirs("src/jvmTest")
        dependencies {
            implementation(libs.compose.ui.test)
            implementation(libs.compose.ui.tooling.preview)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.koin.test)
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
        // so no test reads or writes ~/.singularity-todo and no test mutates a
        // process-global property, which is what made parallel execution safe.
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")

        val tags = (project.findProperty("test.tags") as String?)
            ?.split(",")?.orEmpty() ?: emptyList()
        if (tags.isNotEmpty()) {
            includeTags(*tags.toTypedArray())
        } else {
            // Default: run everything EXCEPT @Tag("slow") — slow requires -Ptest.tags=slow
            excludeTags("slow")
        }
    }
    // Forward `-Dsingularity.*` from the Gradle CLI into the forked test JVM.
    // A `-D` on the Gradle command line configures the daemon, not the test
    // process, so opt-in test switches would otherwise be silently ignored —
    // the flag parses fine and simply has no effect, which is worse than a
    // hard failure. Read from System.getProperties rather than
    // project.findProperty because the CLI form lands on the daemon first.
    // The prefix keeps it to this project's switches.
    // Track each singularity.* key as an input so Gradle config cache is invalidated
    // when any of them change.  Using inputs.property() rather than a plain forEach
    // because the latter is invisible to the configuration-cache.
    listOf(
        "singularity.test.profile",
        "singularity.test.is.android",
        "singularity.test.is.desktop",
        "singularity.test.is.jvm",
        "singularity.test.verbose",
    ).forEach { key -> inputs.property(key) { System.getProperty(key) } }

    // Bumped from default ~512 MB to 3 GB. Forked test JVMs do NOT inherit
    // org.gradle.jvmargs (that's the daemon only). HeapDumpPath is module-local so
    // parallel test runs don't overwrite each other's dumps.
    maxHeapSize = "3g"
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/test-heap-dumps",
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)   // wires ktlint into detekt so detektFormat fixes both
    detektPlugins(project(":detekt-rules"))  // PassThroughUseCaseRule — flags thin wrappers in *UseCase.kt
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

