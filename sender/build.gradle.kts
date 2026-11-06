plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * PhairPlay Sender — the phone half of screen mirroring.
 *
 * Deliberately a separate APK: the TV build must not ask for MediaProjection /
 * capture permissions, and the phone build must not carry the receiver's
 * decoders. No third-party dependencies, so it builds offline and installs on
 * any Android 8+ phone.
 */
android {
    namespace = "com.phairplay.sender"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phairplay.sender"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
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
