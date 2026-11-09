plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.davenn.carbuddy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.davenn.carbuddy"
        // 29 (Android 10) is the first release that lets apps be launched onto,
        // and input be injected into, a specific secondary display.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
        aidl = true
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
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
