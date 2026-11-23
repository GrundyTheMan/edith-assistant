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

rootProject.name = "Edith"
include(":app")
include(":shared")

// Log every build to build-logs/ (gradle/build-log.settings.gradle.kts).
apply(from = "gradle/build-log.settings.gradle.kts")
