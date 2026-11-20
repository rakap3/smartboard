import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared by Papan Guru (:app) and Edu Launcher (:launcher): UI helpers, common icons and the
// contract between the two apps. Like the apps, it uses framework classes only.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.kelasku.core"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
