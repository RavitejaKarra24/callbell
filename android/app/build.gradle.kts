plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.callbell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callbell"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Keystore is generated once by build-android.sh and kept in android/keystore/
    // so later builds install as updates over the previous APK.
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/callbell.jks")
            storePassword = "callbell"
            keyAlias = "callbell"
            keyPassword = "callbell"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
