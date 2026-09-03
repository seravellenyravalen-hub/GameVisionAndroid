plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamevision.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gamevision.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
}
