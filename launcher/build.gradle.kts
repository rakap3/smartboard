import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Edu Launcher: the classroom home screen. Opens Papan Guru (:app) through BoardsContract.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.kelasku.edulauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.kelasku.edulauncher"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        // Package of the Papan Guru build this launcher opens.
        resValue("string", "board_package", "id.kelasku.papanguru")
    }

    buildTypes {
        debug {
            // Same switch as :app, so a dev launcher opens the dev board app installed next to it.
            if (project.hasProperty("devInstall")) {
                applicationIdSuffix = ".dev"
                versionNameSuffix = "-dev"
                manifestPlaceholders["appLabel"] = "Edu Launcher Dev"
                manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_dev"
                resValue("string", "board_package", "id.kelasku.papanguru.dev")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}
