// Stop/go gate of #3234: does FlowMVI 3.2.1 core + compose resolve and compile on the
// app's Kotlin 2.4.10 / Compose 1.12.2 / coroutines 1.11.0 for BOTH targets, with no
// unrelated toolchain upgrade. Kept as a module rather than a one-off command so the
// claim is reproducible after this directory is deleted and re-created.
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.2"
    id("com.android.kotlin.multiplatform.library") version "9.5.1"
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "spike.flowmvi.resolveproof"
        compileSdk = 37
        minSdk = 26
    }

    // FlowMVI's compose artifact declares Compose 1.10.0-beta01 and lifecycle 2.9.5, and
    // silently downgrades the app's 1.12.2 / 2.11.0. Pinning back is what answers "can
    // they coexist at the app's versions".
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("org.jetbrains.compose.")) {
                useVersion("1.12.2")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("pro.respawn.flowmvi:core:3.2.1")
            implementation("pro.respawn.flowmvi:compose:3.2.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
            // Gate 2 of #3234: Koin 4.2.2 and Navigation 3 as the app actually pins them.
            implementation("io.insert-koin:koin-core:4.2.2")
            implementation("io.insert-koin:koin-compose:4.2.2")
            implementation("io.insert-koin:koin-compose-viewmodel:4.2.2")
            implementation("androidx.navigation3:navigation3-runtime:1.2.0")
        }
    }
}
