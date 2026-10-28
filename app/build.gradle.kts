plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hozdogus.pckeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hozdogus.pckeyboard"
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // A fixed key committed to the repo, so every CI build is signed the same
    // way and new APKs install over the old one without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    // Shizuku lets the app send key presses like a real keyboard, without root.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
