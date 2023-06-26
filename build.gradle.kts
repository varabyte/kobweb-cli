import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Plugins declared here instead of settings.gradle.kts because otherwise I get an error saying the kotlin plugin was
// applied multiple times.
@Suppress("DSL_SCOPE_VIOLATION") // https://youtrack.jetbrains.com/issue/KTIJ-19369
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
}

subprojects {
    repositories {
        mavenCentral()
        maven {
            // For kotter snapshots and Kobweb artifacts
            url = uri("https://us-central1-maven.pkg.dev/varabyte-repos/public")
            content {
                includeGroup("com.varabyte.kotter")
                includeGroupByRegex("com.varabyte.kobweb.*")
            }
        }
    }

    // Require Java 11 for a few APIs. A very important one is ProcessHandle, used for detecting if a
    // server is running in a cross-platform way.
    val jvmTarget = JvmTarget.JVM_11
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = jvmTarget.target
        targetCompatibility = jvmTarget.target
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(jvmTarget)
    }
}