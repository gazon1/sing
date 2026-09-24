import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":shared"))

    // AndroidX
    implementation(libs.androidx.activity.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Koin
    implementation(libs.koin.android)

    // Play In-App Updates
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)

    // Compose
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Testing — Android Instrumentation (adb device)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.koin.test)
}

android {
    namespace = "com.singularity.todo"
    compileSdk = libs.versions.sdk.compile.get().toInt()

    defaultConfig {
        applicationId = "com.singularity.todo"
        minSdk = libs.versions.sdk.min.get().toInt()
        targetSdk = libs.versions.sdk.target.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Instrumentation test runner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release") {
            // Load from environment variables or project properties.
            // Never commit keystore passwords to source control.
            // Recommended: set in ~/.gradle/gradle.properties:
            //   singularity.keystore.path=/path/to/keystore
            //   singularity.keystore.password=...
            //   singularity.key.alias=...
            //   singularity.key.password=...
            val keystorePath = System.getenv("SINGULARITY_KEYSTORE_PATH")
                ?: (project.findProperty("singularity.keystore.path") as String?)
            val keystorePassword = System.getenv("SINGULARITY_KEYSTORE_PASSWORD")
                ?: (project.findProperty("singularity.keystore.password") as String?)
            val keyAlias = System.getenv("SINGULARITY_KEY_ALIAS")
                ?: (project.findProperty("singularity.key.alias") as String?)
            val keyPassword = System.getenv("SINGULARITY_KEY_PASSWORD")
                ?: (project.findProperty("singularity.key.password") as String?)

            // If any property is missing, signing is skipped (debug builds use default debug keystore)
            if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Only sign if a keystore is configured (see signingConfigs.release above).
            // Without a keystore the APK is unsigned — fine for local testing but
            // NOT acceptable for Play Store submission.
            val keystoreConfigured = System.getenv("SINGULARITY_KEYSTORE_PATH") != null
                || project.findProperty("singularity.keystore.path") != null
            if (keystoreConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// ---------------------------------------------------------------------------
// detekt — static analysis
// ---------------------------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    ignoreFailures = true
    source.setFrom(
        "src/main/kotlin",
        "src/androidTest/kotlin",
        "src/androidAndroidTest/kotlin"
    )
}
