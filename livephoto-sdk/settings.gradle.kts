pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "filmroom-livephoto-sdk"
include(":livephoto-core", ":livephoto-android", ":demo")
project(":livephoto-core").projectDir = file("core")
project(":livephoto-android").projectDir = file("android")
