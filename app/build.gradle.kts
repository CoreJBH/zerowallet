plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing, fail-closed.
 *
 * A release build is signed only with a real release key, supplied through Gradle
 * properties or the environment:
 *
 *   ZEROWALLET_KEYSTORE          path to the .jks/.keystore
 *   ZEROWALLET_KEYSTORE_PASSWORD store password
 *   ZEROWALLET_KEY_ALIAS         key alias
 *   ZEROWALLET_KEY_PASSWORD      key password
 *
 * With none of them set, `assembleRelease` produces an unsigned APK: it cannot be installed
 * over an existing app, so nobody mistakes it for a shippable artifact. It is deliberately
 * never signed with the debug key, which would make a build look installable while the
 * update fails on every phone that already has the app (or, worse, gets accepted from a
 * laptop instead of the release process). With only some of them set, the build stops here
 * instead of quietly producing something that only looks right: a half-configured release
 * key is a mistake to fix, not a state to build in.
 */
val releaseKeystorePath: String? =
    (findProperty("ZEROWALLET_KEYSTORE") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ZEROWALLET_KEYSTORE")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword: String? =
    (findProperty("ZEROWALLET_KEYSTORE_PASSWORD") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ZEROWALLET_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? =
    (findProperty("ZEROWALLET_KEY_ALIAS") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ZEROWALLET_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? =
    (findProperty("ZEROWALLET_KEY_PASSWORD") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("ZEROWALLET_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val releaseSigningValues =
    listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { it != null }
if (!releaseSigningConfigured && releaseSigningValues.any { it != null }) {
    throw GradleException(
        "Release signing is half configured. Set all of ZEROWALLET_KEYSTORE, " +
            "ZEROWALLET_KEYSTORE_PASSWORD, ZEROWALLET_KEY_ALIAS and ZEROWALLET_KEY_PASSWORD " +
            "(Gradle property or environment variable), or none of them for an unsigned build."
    )
}
if (releaseSigningConfigured && !file(releaseKeystorePath!!).exists()) {
    throw GradleException("ZEROWALLET_KEYSTORE points at ${releaseKeystorePath}, which does not exist.")
}

android {
    namespace = "com.jbitmstr.purewallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jbitmstr.purewallet"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.1.18"

        ndk {
            // Phones are ARM; keeping the APK to the two ARM ABIs halves its size.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // The shipped APK is the debug build until the release keystore secrets exist
            // (see the signing block above; the user is setting those up separately), but a
            // release build still has to work and has to be shippable the moment they do:
            // ldk-node's JNI bridge, the UniFFI bindings and JNA are kept by
            // proguard-rules.pro, and the rest is shrunk.
            isMinifyEnabled = true
            isShrinkResources = true
            // A release build must not expose the debug runtime to a connected adb shell or a
            // crash reporter that expects debuggable code. Both of these are stated rather
            // than inherited, so a future change to the debug block cannot leak into release:
            // - isDebuggable=false drops run-as, the debugger and the JDWP port;
            // - isJniDebuggable=false does the same for the native side (ldk-node's .so),
            //   which is where a wallet's channel state actually lives.
            isDebuggable = false
            isJniDebuggable = false
            // Null when no release key is configured, which leaves the APK unsigned rather
            // than falling back to the debug key.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // App lock: biometrics with the device screen lock as the fallback.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Real LDK node: Lightning + on-chain wallet, running fully on the device.
    implementation("org.lightningdevkit:ldk-node-android:0.7.0")

    // Camera QR scanning.
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // QR code rendering for receive / invoice screens.
    implementation("com.google.zxing:core:3.5.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, so the fee parser is tested against the same class the app uses on
    // Android instead of the unit-test stub whose methods return defaults.
    testImplementation("org.json:json:20240303")
}
