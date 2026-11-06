plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets["commonMain"].apply {
        kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin"))
        dependencies {
            implementation(project(":module"))
        }
    }
}
