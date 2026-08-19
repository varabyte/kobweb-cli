// Plugins declared here instead of settings.gradle.kts because otherwise I get an error saying the kotlin plugin was
// applied multiple times.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
}
