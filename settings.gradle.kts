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

rootProject.name = "photo-manager"

include(
    ":app",
    ":core:common",
    ":core:ui",
    ":domain",
    ":data:preferences",
    ":feature:home",
    ":feature:photos",
    ":feature:people",
    ":feature:search",
    ":feature:settings",
)
