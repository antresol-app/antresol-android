import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.antresol.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.antresol.mobile"
        minSdk = 26
        targetSdk = 36
        // versionName, versionCode, R8 and signing: see the root build.gradle.kts.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The Robolectric UI test drives the app against a live server; it runs only when
            // ANTRESOL_TEST_SERVER is set (see NOTES.md).
            val liveServer = System.getenv("ANTRESOL_TEST_SERVER") ?: ""
            it.inputs.property("antresolTestServer", liveServer)
            if (liveServer.isEmpty()) it.filter.excludeTestsMatching("*LiveUiTest")
            it.maxHeapSize = "2g"
        }
    }
}

// The live UI tests of both apps sign in as the same profile and play the same items; running them at the same time
// (org.gradle.parallel) lets one app's progress snapshot capture the other's playback. Run the phone's after the TV's.
tasks.withType<Test>().configureEach { mustRunAfter(":tv:testDebugUnitTest") }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

baselineProfile {
    // The profile is made on an emulator against scripts/fake-server.py and checked in
    // (src/release/generated/baselineProfiles, see NOTES.md); a normal build never runs a device.
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

composeCompiler {
    // The :core models are immutable; see the file.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(project(":ui-common"))
    // Installs the app's Baseline Profile on devices that do not get it from Play (sideloads, other stores).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":mobile-baselineprofile"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    // «Sign in a TV»: camera frames for the QR reader (decoding is ZXing core in :core). Apache-2.0, no Play services.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
