import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()
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
