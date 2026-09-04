plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamevision.companion"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.gamevision.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
