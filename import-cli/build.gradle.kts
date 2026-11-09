import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
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
    mainClass.set("com.singularity.todo.importer.MainKt")
    applicationName = "singularity-import"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoreFailures = false
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

dependencies {
    // The project's own rules; without them the shared config reports every
    // custom property as invalid and the task cannot pass.
    detektPlugins(project(":detekt-rules"))
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = true
    useJUnitPlatform()
}
