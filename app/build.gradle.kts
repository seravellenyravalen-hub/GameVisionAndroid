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
        versionCode = 5
        versionName = "1.2.2"
    }
}

// Keep the checked-in app compatible with older source snapshots while ensuring
every Android HTTP client is rewritten to the current Render backend at build time.
val configureProductionBackend by tasks.registering {
    doLast {
        val sourceRoot = file("src")
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { source ->
                val text = source.readText()
                val updated = text.replace(
                    "https://gamevision-api-v2-production.up.railway.app",
                    "https://gamevision-api.onrender.com"
                )
                if (updated != text) source.writeText(updated)
            }
    }
}

tasks.named("preBuild") {
    dependsOn(configureProductionBackend)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
