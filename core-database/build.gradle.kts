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
