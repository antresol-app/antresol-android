pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "antresol-android"

include(":core", ":ui-common", ":tv", ":mobile")
// Baseline Profile generators and Macrobenchmarks of the two apps (run on an emulator, see NOTES.md).
include(":mobile-baselineprofile", ":tv-baselineprofile")
