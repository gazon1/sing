import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

sourceSets {
    test {
        java.srcDirs("src/jvmTest")
        dependencies {
            implementation(libs.compose.ui.test)
            implementation(libs.compose.ui.tooling.preview)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.koin.test)
            implementation(libs.koin.core)
            implementation(libs.junit)
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.koin.core)
    implementation(libs.compose.material3)
    implementation(libs.compose.material)
    implementation(libs.coil.compose)

    implementation(libs.compose.ui.tooling.preview)

    // Room for desktop database
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite)
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

// ---------------------------------------------------------------------------
// detekt — static analysis + ktlint (via detekt-formatting plugin)
// ---------------------------------------------------------------------------
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-desktopApp.xml")
    ignoreFailures = true               // report-only on day 1
    source.setFrom("src/main/kotlin", "src/jvmTest/kotlin")
}

dependencies {
    detektPlugins(libs.detekt.formatting)   // wires ktlint into detekt so detektFormat fixes both
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

