import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
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
    implementation(libs.koin.annotations.runtime)
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
    testImplementation(libs.jvm.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(kotlin("reflect"))

    detektPlugins(libs.detekt.formatting)
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

// ---------------------------------------------------------------------------
// detekt — static analysis
// ---------------------------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    ignoreFailures = true
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin"
    )
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
