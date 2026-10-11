import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.detekt)
}

/**
 * `:core-base` — the platform facts that are not about any feature.
 *
 * `HostEnvironmentPort`, `Clock` and `TimeZoneProvider` are here, in the package they
 * always had, because `:adr-core` needs them and `:shared` must not be a dependency of
 * a command-line tool that lists markdown files.
 *
 * The package name is deliberately unchanged. 52 files in `:shared` import
 * `com.singularity.todo.core.platform`; keeping the name means moving the module is a
 * build-graph change and nothing else. Renaming would have turned a five-file move into
 * a 52-file diff, and the size of that diff is what makes review give up.
 *
 * **No Compose, no Room, no Koin, no Koog.** That is the entire point: `:shared` brings
 * all four, so anything wanting a clock without an application pays for an application.
 */
kotlin {
    jvm()

    android {
        namespace = "com.singularity.todo.corebase"
        compileSdk = libs.versions.sdk.compile.get().toInt()
        minSdk = libs.versions.sdk.min.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            freeCompilerArgs.add("-Xskip-metadata-version-check")
            freeCompilerArgs.add("-Xbinary=allow-kotlin-metadata-version-mismatch=true")
            freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
    }
}
// detekt — same rules as every other module, no exceptions. A module without detekt
// is a module whose findings nobody reads, and the wiring gate counts configured
// tasks rather than modules that happen to have one.
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-adr-core.xml")
    ignoreFailures = false
    source.setFrom("src/commonMain/kotlin", "src/jvmMain/kotlin", "src/androidMain/kotlin")
}

dependencies {
    detektPlugins(libs.detekt.formatting)
    detektPlugins(project(":detekt-rules"))
}
