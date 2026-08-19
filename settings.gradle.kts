

import org.gradle.kotlin.dsl.repositories

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("gradle/build-logic")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()

        // Special Gradle repo for gradle tooling library
        exclusiveContent {
            forRepository {
                maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
            }
            filter {
                includeGroup("org.gradle")
            }
        }

        maven {
            // For kotter snapshots and Kobweb artifacts
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent {
                includeGroup("com.varabyte.kotter")
                includeGroupByRegex("com\\.varabyte\\.kobwebx?")
                snapshotsOnly()
            }
        }
    }
}

rootProject.name = "kobweb-cli"

include(":kobweb")