import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Require Java 11 for a few APIs. A very important one is ProcessHandle, used for detecting if a
// server is running in a cross-platform way.
val jvmTarget = JvmTarget.JVM_11
tasks.withType<JavaCompile>().configureEach {
    options.release.set(JvmTarget.JVM_11.target.toInt())
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(jvmTarget)
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=${JvmTarget.JVM_11.target}")
}
