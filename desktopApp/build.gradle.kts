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

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "singularity-todo"
            packageVersion = "0.1.0"
        }
    }
}

