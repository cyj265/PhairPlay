import java.util.Properties

// App module build configuration for PhairPlay.
//
// Two product flavors are defined from the start:
//   - "googletv": targets Google TV / Android TV (minSdk 29)
//   - "firetv":   targets Amazon Fire TV (minSdk 25)
//
// Shared code lives in src/main/. Flavor-specific overrides in src/googletv/ and src/firetv/.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun String.escapedForBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

val castAppId: String =
    (providers.gradleProperty("phairplay.castAppId").orNull
        ?: providers.environmentVariable("PHAIRPLAY_CAST_APP_ID").orNull
        ?: "").trim()

// Keystore settings: environment first, then the git-ignored local.properties.
// This is the only place the signing key can come from — the values are never
// written into this file, and app/phairplay-signing.jks is not tracked.
// Read eagerly, not as a `by lazy` delegate: delegated top-level properties do
// not resolve in a Gradle .kts script, and the keystore is only touched here.
val localKeystoreProperties: Map<String, String> = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    props.entries.associate { it.key.toString() to it.value.toString() }
}

fun signingSetting(name: String): String? {
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return localKeystoreProperties[name]?.trim()?.takeIf { it.isNotEmpty() }
}

/** False when this machine has no keystore: debug/release then fall back to the stock debug key. */
val hasPhairplayKeystore: Boolean = signingSetting("KEYSTORE_PASSWORD") != null

// Debug and release must always select the SAME config between them, otherwise a
// release APK could not cover-install over a debug one.
val signingConfigName = if (hasPhairplayKeystore) "phairplay" else "debug"

if (!hasPhairplayKeystore) {
    logger.warn(
        "PhairPlay: no keystore configured (KEYSTORE_PASSWORD unset and absent from " +
            "local.properties). Falling back to the stock debug signing config, so APKs " +
            "built here will NOT over-install an existing PhairPlay build. Copy " +
            "local.defaults.properties to local.properties and fill in the keystore."
    )
}

android {
    namespace = "com.phairplay"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // applicationId is overridden per flavor below
        minSdk = 25           // Lowest common denominator (Fire TV)
        targetSdk = 35
        versionCode = 63
        versionName = "1.0.111"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CAST_APP_ID", "\"${castAppId.escapedForBuildConfig()}\"")

        // Native FairPlay (libplayfair.so) — build for all Android ABIs so PhairPlay runs on
        // the full range of Android TV / Fire TV hardware (32- and 64-bit ARM, plus x86/x86_64
        // for Intel devices, ChromeOS, and emulators). Required for Google Play 64-bit compliance.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // Native build: RPiPlay's FairPlay (playfair) compiled via CMake → libplayfair.so.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Two flavors: one for Google TV, one for Amazon Fire TV.
    // This separation allows flavor-specific code, resources, and dependencies.
    flavorDimensions += "platform"
    productFlavors {
        create("googletv") {
            dimension = "platform"
            applicationId = "com.phairplay.googletv"
            minSdk = 29        // Google TV requires Android 10+
            versionNameSuffix = "-googletv"
        }
        create("firetv") {
            dimension = "platform"
            applicationId = "com.phairplay.firetv"
            minSdk = 25        // Fire TV supports Android 7.1+
            versionNameSuffix = "-firetv"
        }
    }

    // Unified signing: every APK this project produces shares one signature, so
    // any APK can cover-install over any other on the box.
    //
    // The keystore lives outside version control on purpose. It is read from an
    // environment variable first, then from the git-ignored local.properties
    // (see local.defaults.properties for the full list of keys). Nothing is
    // hard-coded here: a published password lets anyone re-sign the same
    // package name and cover-install over an installed copy of your app.
    //
    // On a machine without those settings both debug and release fall back to
    // the stock debug signing config, so the build still runs (and CI stays
    // green) — it just produces a different, non-upgradeable signature. The
    // configuration phase prints a warning when that happens.
    signingConfigs {
        create("phairplay") {
            storeFile = file(signingSetting("KEYSTORE_FILE") ?: "phairplay-signing.jks")
            storePassword = signingSetting("KEYSTORE_PASSWORD").orEmpty()
            keyAlias = signingSetting("KEY_ALIAS").orEmpty()
            keyPassword = signingSetting("KEY_PASSWORD").orEmpty()
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName(signingConfigName)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(signingConfigName)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Jetty 9.4 uses Java 8 APIs (java.util.function, stream, …); core
        // library desugaring backports them so the Fire TV flavor (minSdk 25)
        // can dex and run them on Android 7.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        // Enable strict coroutine checks in debug builds
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    // Source sets: shared code in main, flavor-specific overrides in flavor directories
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
        }
        getByName("googletv") {
            kotlin.srcDirs("src/googletv/kotlin")
            res.srcDirs("src/googletv/res")
        }
        getByName("firetv") {
            kotlin.srcDirs("src/firetv/kotlin")
            res.srcDirs("src/firetv/res")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    // Lint configuration: treat all warnings as errors in CI
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Keep lint focused on PhairPlay sources. The Google Cast SDK pulls a
        // large transitive graph that exceeds the small CI/dev VM during
        // dependency lint analysis, while app-source lint still catches local
        // manifest/resource/API regressions.
        checkDependencies = false
        disable += setOf(
            // Dependency freshness is tracked intentionally, but should not block
            // protocol/build CI when the pinned toolchain is known-good.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            // Localizations are incomplete during the pre-release hardware-test phase.
            "MissingTranslation",
            // Cleanup/style issues that should not block debug APK CI.
            "ButtonStyle",
            "DataExtractionRules",
            "DiscouragedApi",
            "MonochromeLauncherIcon",
            // Launcher-icon shape is advisory; on Android TV the banner is the primary
            // artwork and the icon is rarely shown (sibling of MonochromeLauncherIcon above).
            "IconLauncherShape",
            "ObsoleteSdkInt",
            "Overdraw",
            "UnusedResources",
            // Advisory: the project deliberately supports a wide API range for old TVs;
            // targetSdk is bumped deliberately, not on every new platform release.
            "OldTargetApi",
            // Media3's @UnstableApi opt-in is correctly annotated at compile time
            // (class-level @OptIn); the lint checker falsely flags usage inside lambdas.
            "UnsafeOptInUsageError"
        )
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
        resources {
            // BouncyCastle (and some other crypto libs) include OSGI manifest files
            // that conflict when multiple jars are merged. Exclude them — they are
            // not needed at runtime on Android (OSGI is a Java EE/OSGi framework).
            excludes += "META-INF/versions/9/OSGI-INF/**"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

    buildFeatures {
        // BuildConfig is disabled by default in AGP 8.x — enable it explicitly
        // because PhairPlayApp.kt and SettingsFragment.kt use BuildConfig.VERSION_NAME etc.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX UI (View-based, for maximum TV compatibility)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    // Leanback — TV focus management, on-screen keyboard, TV-specific widgets
    implementation(libs.androidx.leanback)

    // DataStore — async, type-safe replacement for SharedPreferences
    implementation(libs.androidx.datastore.preferences)

    // Async I/O — all network and media operations use coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging — tagged, level-filtered logs with pluggable backend
    implementation(libs.timber)

    // Cryptography — AES-128-CTR for audio decryption, future SRP-6a pairing
    implementation(libs.bouncycastle)

    // Binary property lists — AirPlay 2 handshake payloads (GET /info, SETUP)
    implementation(libs.ddplist)

    // DLNA/UPnP — jUPnP renderer stack (SSDP discovery + AVTransport control)
    // Exclude slf4j 2.x: its ServiceLoader-based provider discovery is
    // unreliable on Android and can surface as NoClassDefFoundError at runtime.
    // slf4j 1.7.x + slf4j-android statically binds logging to Logcat — the
    // configuration jUPnP's own Android demo ships with.
    implementation(libs.jupnp) { exclude(group = "org.slf4j") }
    implementation(libs.jupnp.support) { exclude(group = "org.slf4j") }
    implementation(libs.jupnp.android) { exclude(group = "org.slf4j") }
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // Java 8 API backport for minSdk 25 (see compileOptions above)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Media3 (ExoPlayer) — DLNA video playback (HLS / MP4 / DASH)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    // OkHttp — HTTP transport with a custom IPv4-only Dns (see Ipv4HttpDataSource).
    implementation(libs.okhttp)

    // Google TV Cast Connect receiver SDK. Kept out of the Fire TV flavor because
    // Fire TV lacks Google Play Services and cannot run Google Cast receiver APIs.
    "googletvImplementation"(libs.play.services.cast.tv)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric — real Android framework classes (Intent, Base64, …) in JVM unit tests
    testImplementation(libs.robolectric)

    // Instrumented Testing (on device)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
