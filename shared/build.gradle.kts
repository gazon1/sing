import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidMultiplatformLibrary)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
	alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    android {
        namespace = "com.singularity.todo.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            freeCompilerArgs.add("-Xskip-metadata-version-check")
            freeCompilerArgs.add("-Xbinary=allow-kotlin-metadata-version-mismatch=true")
            freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Compose
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)

            // Lifecycle
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)

            // Serialization
            implementation(libs.kotlinx.serialization.json)

            // Room (runtime only for common - platform-specific drivers in each target)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

						// Koin
						implementation(libs.koin.core)
						implementation(libs.koin.compose)
						implementation(libs.koin.compose.viewmodel)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.json)

            // DataStore
            implementation(libs.androidx.datastore.preferences.core)

            // Kermit
            implementation(libs.kermit)

            // Markdown
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)

            // Rich Text Editor
            implementation(libs.rich.editor.compose)

            // OpenAI (legacy client — replaced by Koog in Phase 5)
            implementation(libs.openai.client)

            // Koog AI Agent Framework (JetBrains) — Phase 5 real integration
            implementation(libs.koog.agents)
            implementation(libs.koog.prompt.executor.openai.client)
            implementation(libs.koog.prompt.llm)
            implementation(libs.koog.prompt.executor.model)

            // MaterialKolor
            implementation(libs.materialkolor)

            // FileKit
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)

            // Coil
            implementation(libs.coil.compose)
            implementation(libs.coil.core)

            // Voyager
            implementation(libs.voyager.navigator)
            implementation(libs.voyager.tab.navigator)
            implementation(libs.voyager.koin)
            implementation(libs.voyager.screenmodel)

            // Utils
            implementation(libs.ulid)

            // Supabase
            implementation("io.github.jan-tennert.supabase:auth-kt:3.8.0")
            implementation("io.github.jan-tennert.supabase:postgrest-kt:3.8.0")
            implementation("io.github.jan-tennert.supabase:functions-kt:3.8.0")
        }

        androidMain.dependencies {
            implementation(libs.compose.uiTooling)
            implementation(libs.compose.uiToolingPreview)

            // Room
            implementation(libs.androidx.room.runtime)

            // Koin Android
            implementation(libs.koin.android)

            // Ktor OkHttp
            implementation(libs.ktor.client.okhttp)

            // DataStore
            implementation(libs.androidx.datastore.preferences)

            // FileKit Android
            implementation(libs.filekit.core)

            // Security — EncryptedSharedPreferences
            implementation(libs.android.security.crypto)
        }

        jvmMain.dependencies {
            // Room
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.sqlite.jdbc)

            // Koin
            implementation(libs.koin.core)

            // Ktor CIO
            implementation(libs.ktor.client.cio)

            // DataStore (full artifact includes JVM factory)
            implementation(libs.androidx.datastore.preferences)

            // DateTime
            implementation(libs.kotlinx.datetime)

            // Coil Ktor network
            implementation(libs.coil.network.ktor3)

            // Koog OkHttp HTTP backend — JVM-only
            implementation(libs.koog.http.client.okhttp)

            // Koog OpenAI client — JVM-only (multiplatform artifact resolves to android stub
            // in KMP context; explicit -jvm dep needed for JVM target compile classpath)
            implementation(libs.koog.prompt.executor.openai.client.jvm)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        jvmTest.dependencies {
            implementation(libs.sqlite.jdbc)
            implementation(libs.androidx.room.testing)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" && requested.name == "atomicfu") {
            useVersion("0.23.1")
        }
        if (requested.group == "org.jetbrains.kotlinx" && requested.name == "kotlinx-collections-immutable") {
            useVersion("0.3.7")
        }
        if (requested.group == "org.jetbrains.kotlinx" && requested.name == "kotlinx-serialization-json") {
            useVersion("1.11.0")
        }
        if (requested.group == "org.jetbrains.kotlinx" && requested.name == "kotlinx-serialization-core") {
            useVersion("1.11.0")
        }
    }
}

