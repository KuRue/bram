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

rootProject.name = "Bram"

include(":app")
include(":core:domain")
include(":core:agent")
include(":platform:android")
include(":runtime:openai")
include(":runtime:llamacpp")
