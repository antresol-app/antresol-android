import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

dependencies {
    implementation(project(":ui-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
