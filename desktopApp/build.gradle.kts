import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.koin.core)
    implementation(libs.compose.material3)
    implementation(libs.coil.compose)

    implementation(libs.compose.uiToolingPreview)

    // Room for desktop database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
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

