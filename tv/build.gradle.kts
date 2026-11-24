import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.antresol.tv"
    compileSdk = 36

    defaultConfig {
        // One Play listing for phone and TV: the TV build ships under the phone app's package on Play's
        // Android TV form-factor track (docs/PLAY_STORE.md). The code namespace stays app.antresol.tv.
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
            // ANTRESOL_TEST_SERVER is set (see NOTES.md), so CI does not download Android runtimes for it.
            val liveServer = System.getenv("ANTRESOL_TEST_SERVER") ?: ""
            it.inputs.property("antresolTestServer", liveServer)
            if (liveServer.isEmpty()) it.filter.excludeTestsMatching("*LiveUiTest")
            it.maxHeapSize = "2g"
        }
    }
}

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
    baselineProfile(project(":tv-baselineprofile"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.tv.material)
    // Watch Next and the «Antresol» preview channel on the TV home screen (PreviewChannelHelper), refreshed by WorkManager.
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
