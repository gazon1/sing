import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

// Disable distribution tasks — we only need the application JAR
tasks.named("distZip").configure { enabled = false }
tasks.named("distTar").configure { enabled = false }

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xskip-metadata-version-check")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

application {
    mainClass.set("com.singularity.todo.mcp.MainKt")
}

dependencies {
    // MCP SDK — stdio transport + server
    implementation("io.modelcontextprotocol:kotlin-sdk:0.15.0")

    // Reuse shared KMP library (Room KMP driver, all domain)
    implementation(project(":shared"))

    // Koog agents + tools — needed for SimpleTool, ToolDescriptor, TypeToken APIs
    // (shared exposes them via implementation, so we re-add here)
    implementation(libs.koog.agents)

    // Koin Annotations runtime (for @Module/@ComponentScan processing)
    implementation(libs.koin.annotations.runtime)

    // Koin core — required at compile time; koin-annotations-jvm does NOT bring it transitively
    implementation(libs.koin.core)

    // Kermit Koin integration (JVM logging bridge for Koin)
    implementation(libs.kermit.koin)

    // Room 3 KMP — compile-time deps needed by :shared's AppDatabase
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite)
    compileOnly(libs.androidx.room3.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Serialization (MCP protocol uses JSON)
    implementation(libs.kotlinx.serialization.json)

    // DateTime
    implementation(libs.kotlinx.datetime)

    // Kermit
    implementation(libs.kermit)

    // kotlinx-io JVM — provides kotlinx.io.Source/Sink, RawSource.buffered(), InputStream.asSource()
    // Version: 0.9.1 (transitive via ktor-io-jvm, but explicit for compiler visibility).
    implementation("org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1")

    // Ktor IO JVM — provides InputStream.toByteReadChannel() + ByteReadChannel.asSource()
    // bridge to kotlinx-io Source/Sink for StdioServerTransport.
    // Version pinned to 3.5.2 (transitive via ktor-server-*, must match).
    implementation("io.ktor:ktor-io-jvm:3.5.2")
}
