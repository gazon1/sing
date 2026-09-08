import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
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
    implementation("io.modelcontextprotocol:kotlin-sdk:0.15.0")
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
    implementation("org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1")
    implementation("io.ktor:ktor-io-jvm:3.5.2")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(kotlin("reflect"))
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
