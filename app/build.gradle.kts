plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.dino.launcher"
    compileSdk = 34
    signingConfigs {
        create("dino") {
            storeFile = file("dino.jks")
            storePassword = "dinolauncher"
            keyAlias = "dino"
            keyPassword = "dinolauncher"
        }
    }
    defaultConfig {
        applicationId = "com.dino.launcher"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("dino")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
