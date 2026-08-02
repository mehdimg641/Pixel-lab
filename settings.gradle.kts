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

rootProject.name = "pixel-lab"

include(":core:model")
include(":core:text")
include(":core:fonts")
include(":core:render")
include(":core:canvas")
include(":core:editor")
include(":core:codec")
include(":core:imaging")
include(":core:paint")
include(":engine:android")
include(":app:android")
