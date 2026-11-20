import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.kelasku.papanguru"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.kelasku.papanguru"
        minSdk = 23
        targetSdk = 35
        versionCode = 16
        versionName = "0.11.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        manifestPlaceholders["appBanner"] = "@drawable/banner"
        // Classroom sync server is plain http:// during development (docs/blueprint-kelas-siswa.md §11).
        // Only debug builds may use cleartext; release builds must talk to an HTTPS server.
        manifestPlaceholders["cleartext"] = "false"
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartext"] = "true"
            // `./gradlew -PdevInstall :app:installDebug` installs next to a copy signed with another key.
            if (project.hasProperty("devInstall")) {
                applicationIdSuffix = ".dev"
                versionNameSuffix = "-dev"
                manifestPlaceholders["appLabel"] = "Papan Guru Dev"
                manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_dev"
                manifestPlaceholders["appBanner"] = "@drawable/banner_dev"
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
    // Test-only: the app has no third-party runtime dependencies. org.json is stubbed in android.jar on the JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
