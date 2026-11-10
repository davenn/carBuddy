plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.davenn.carbuddy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.davenn.carbuddy"
        // 29 (Android 10): foreground service types, needed for screen capture.
        // Android Auto's parked apps themselves need Android 15+ on the phone.
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.1"
    }

    signingConfigs {
        // A fixed debug key committed to the repo, so an APK built anywhere
        // (any cloud session, CI, a laptop) installs over the previous one
        // instead of failing with "signatures do not match". It signs a
        // sideloaded test app only; it is not a release key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
}
