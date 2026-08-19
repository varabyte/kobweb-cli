pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("gradle/build-logic")
}

rootProject.name = "kobweb-cli"

include(":kobweb")