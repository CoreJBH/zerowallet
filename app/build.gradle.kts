import java.io.File
import java.util.Properties

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
 * They may instead live in `keystore/keystore.properties` at the repository root (storeFile,
 * storePassword, keyAlias, keyPassword), which is never committed - the same place the sibling
 * app keeps its own key. Gradle properties and environment variables win over the file, so CI
 * passes them as secrets and a developer's checkout keeps them on disk.

 * With none of them set, a build that would write a release artifact stops here instead of
 * writing one unsigned: nobody should mistake an artifact for something shippable that
 * cannot install over the app. It is deliberately never signed with the debug key, which
 * would make a build look installable while the update fails on every phone that already has
 * the app (or, worse, gets accepted from a laptop instead of the release process). With only
 * some of them set, the build also stops instead of quietly producing something that only
 * looks right: a half-configured release key is a mistake to fix, not a state to build in.
 *
 * A builder that signs the artifact with its own key - F-Droid builds `assembleRelease` in a
 * checkout that has no keystore and signs the APK afterwards with the F-Droid key - asks for
 * an unsigned release artifact explicitly with `-Pzerowallet.unsignedRelease=true`. The flag
 * is the only way the release build runs without signing material, and it is never inferred.
 * Code checks (unit tests, lint, dependency reports) run without any signing material, and
 * `assembleDebug` is unaffected - only a task that packages a release needs this decision.
 */
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

/** A signing value: Gradle property, then environment, then the local properties file. */
fun signingValue(gradleProperty: String, envName: String, fileKey: String): String? =
    (findProperty(gradleProperty) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(fileKey)?.takeIf { it.isNotBlank() }

val releaseKeystorePath: String? =
    signingValue("ZEROWALLET_KEYSTORE", "ZEROWALLET_KEYSTORE", "storeFile")
val releaseKeystorePassword: String? =
    signingValue("ZEROWALLET_KEYSTORE_PASSWORD", "ZEROWALLET_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? =
    signingValue("ZEROWALLET_KEY_ALIAS", "ZEROWALLET_KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? =
    signingValue("ZEROWALLET_KEY_PASSWORD", "ZEROWALLET_KEY_PASSWORD", "keyPassword")

/** The keystore file itself: absolute as given, or resolved from the module then the root. */
val releaseKeystoreFile: File? = releaseKeystorePath?.let { raw ->
    val direct = File(raw)
    when {
        direct.isAbsolute -> direct
        file(raw).exists() -> file(raw)
        else -> rootProject.file(raw)
    }
}

val releaseSigningValues =
    listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { it != null }

// A builder that signs the artifact with its own key (F-Droid does: it builds in a checkout
// with no keystore and signs the APK afterwards with the F-Droid key) asks for an unsigned
// release artifact explicitly. Nothing infers it: without the flag a release build that
// cannot be signed stops, and the debug key is never used for a release.
val unsignedReleaseRequested =
    providers.gradleProperty("zerowallet.unsignedRelease").orNull
        ?.equals("true", ignoreCase = true) == true

if (!unsignedReleaseRequested) {
    if (!releaseSigningConfigured && releaseSigningValues.any { it != null }) {
        throw GradleException(
            "Release signing is half configured. Set all of ZEROWALLET_KEYSTORE, " +
                "ZEROWALLET_KEYSTORE_PASSWORD, ZEROWALLET_KEY_ALIAS and ZEROWALLET_KEY_PASSWORD " +
                "(Gradle property or environment variable), or none of them and pass " +
                "-Pzerowallet.unsignedRelease=true for an unsigned build."
        )
    }
    if (releaseSigningConfigured && releaseKeystoreFile?.exists() != true) {
        throw GradleException("ZEROWALLET_KEYSTORE points at ${releaseKeystorePath}, which does not exist.")
    }
}

// Tasks that write a release artifact, and therefore need the signature (or an explicit
// request for an unsigned one). Lint, unit tests and dependency reports only read the code
// and sign nothing, so they keep running in a checkout without a key.
fun taskNeedsReleaseSignature(request: String): Boolean {
    val task = request.substringAfterLast(':').lowercase()
    val codeCheck = task.startsWith("lint") || task.startsWith("test") ||
        task.startsWith("connected") || task.startsWith("dependencies") ||
        task.startsWith("dependency") || task == "tasks" || task == "help"
    if (codeCheck) return false
    return task.contains("release") || task in setOf(
        "assemble", "bundle", "build", "install", "publish", "package", "sign"
    )
}

if (!releaseSigningConfigured && !unsignedReleaseRequested &&
    gradle.startParameter.taskNames.any { taskNeedsReleaseSignature(it) }
) {
    throw GradleException(
        "Release signing is not configured (none of ZEROWALLET_KEYSTORE, " +
            "ZEROWALLET_KEYSTORE_PASSWORD, ZEROWALLET_KEY_ALIAS and ZEROWALLET_KEY_PASSWORD is set, " +
            "and keystore/keystore.properties is absent). A release build is not produced unsigned, " +
            "and never with the debug key; a builder that signs the artifact with its own key " +
            "passes -Pzerowallet.unsignedRelease=true, and a signed build sets the four values."
    )
}

// Signing is used only when the key is fully configured and no unsigned artifact was asked for.
val signedRelease = releaseSigningConfigured && !unsignedReleaseRequested

android {
    // The package is the publisher's own name, carried over from the GitHub handle the project
    // moved to. It became this app's identity before the first store release, which is the only
    // moment it can still change: applicationId is what Android uses to recognize an update, so
    // from the first published build on it is fixed for good. A build with a different
    // applicationId is a different app to the system - it installs beside an older wallet
    // instead of over it, starts with an empty data directory, and the way back to the funds in
    // the old install is the recovery phrase or a `.zwbackup` export (the backup's key comes
    // from the phrase alone, so it opens here unchanged).
    //
    // The Kotlin package, the namespace and the applicationId now all read the same, so
    // BuildConfig, R, the tests and ProGuard cannot drift apart. The Keystore aliases and
    // preference file names inside the app are not part of this rename and are frozen at what
    // earlier builds wrote (see SecureStore.KEY_ALIAS): they address this app's own stored
    // ciphertext, and a new name for them would buy nothing.
    namespace = "com.corejbh.zerowallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.corejbh.zerowallet"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.2.3"

        // Instrumented tests (Keystore, BiometricPrompt, FileProvider) run on a device with
        // `connectedDebugAndroidTest`; they are not part of CI, which has no device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Phones are ARM; keeping the APK to the two ARM ABIs halves its size.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (signedRelease) {
            create("release") {
                storeFile = releaseKeystoreFile
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
            // Null when the build was asked for an unsigned artifact (F-Droid), which leaves
            // the APK unsigned rather than falling back to the debug key.
            signingConfig = if (signedRelease) signingConfigs.getByName("release") else null
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

    // On-device tests for the parts a JVM cannot have: a Keystore key, a biometric prompt and
    // the FileProvider the backup is shared through.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // The real org.json, so the fee parser is tested against the same class the app uses on
    // Android instead of the unit-test stub whose methods return defaults.
    testImplementation("org.json:json:20240303")
}
