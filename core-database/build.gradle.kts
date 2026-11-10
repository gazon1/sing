import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions {
        }
    }

    android {
        namespace = "com.singularity.todo.coredatabase"
        compileSdk =
            libs.versions.sdk.compile
                .get()
                .toInt()
        minSdk =
            libs.versions.sdk.min
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core-domain"))
            api(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
        }
        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        androidMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
    detektPlugins(project(":detekt-rules"))
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

/*
 * `:core-database:koverGenerateArtifact` consumes the JVM target's compiled
 * classes and the KSP-generated sources next to them, and Gradle 9 reports both
 * as implicit dependencies unless one side declares them. CI hit it on
 * 2026-11-09 during `:koverVerify`:
 *
 *     Task ':core-database:koverGenerateArtifact' uses this output of task
 *     ':core-database:kspKotlinJvm' without declaring an explicit or implicit dependency
 *
 * and, once that one is declared, the same error for
 * `:core-database:compileKotlinJvm` (`build/classes/kotlin/jvm/main`).
 * `:adr-core` needed the same fix for `compileKotlinJvm` alone — see
 * `adr-core/build.gradle.kts`.
 *
 * It reproduces only on a cold `core-database/build`: the validation fires when
 * the output directory does not already exist, so a warm `build/` hides it.
 *
 * `dependsOn("…")` by name rather than `tasks.named(...)`: the KSP plugin
 * registers its tasks lazily, and a string dependency is resolved when the task
 * graph is built, so this cannot fail configuration.
 */
tasks.matching { it.name == "koverGenerateArtifact" }.configureEach {
    dependsOn("compileKotlinJvm", "kspKotlinJvm")
}

// detekt — same rules as every other module. The moved database files were in
// `:shared`; its baseline is keyed by file name, so sharing it keeps the
// repository's accepted debt suppressed after the move instead of reporting it
// again as new.
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-shared.xml")
    buildUponDefaultConfig = true
    ignoreFailures = false
    source.setFrom("src/commonMain/kotlin", "src/jvmMain/kotlin", "src/androidMain/kotlin")
}
