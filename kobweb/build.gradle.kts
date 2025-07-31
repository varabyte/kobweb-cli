import org.jreleaser.model.Active

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.shadow)
}

group = "com.varabyte.kobweb.cli"
version = libs.versions.kobweb.cli.get()

repositories {
    // For Gradle Tooling API
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    // TODO: Remove this repository once clikt-core module is published https://github.com/ajalt/clikt/issues/523
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotter)
    implementation(libs.freemarker)
    implementation(libs.okhttp)
    implementation(libs.kobweb.common)

    // For Gradle Tooling API (used for starting up / communicating with a gradle daemon)
    implementation("org.gradle:gradle-tooling-api:${gradle.gradleVersion}")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.6") // Needed by gradle tooling
}

application {
    applicationDefaultJvmArgs = listOf(
        "-Dkobweb.version=${version}",
        // JDK24 started reporting warnings for libraries that use protected native methods, at least one (System.load)
        // which Kotter uses (via jline/jansi). Since Java fat jars built by Kotlin don't really use Java's module
        // system, we unfortunately have to whitelist all unnamed modules. We also enable the
        // IgnoreUnrecognizedVMOptions flag to avoid causing users running older versions of the JVM to crash.
        // See also: https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/doc-files/RestrictedMethods.html
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--enable-native-access=ALL-UNNAMED",
    )
    mainClass.set("MainKt")
}

// Useful for CLI
tasks.register("printVersion") {
    doLast {
        println(version.toString())
    }
}

// We used to minimize this jar, but it kept causing us problems. At our most recent check a minimized jar was 11MB vs.
// 12MB not minimized. It's just not worth the surprising crashes!
// Just in case we decide to minimize again someday, here's what we were doing.
//tasks.withType<ShadowJar>().configureEach {
//    minimize {
//        // Leave Jansi deps in place, or else Windows won't work
//        exclude(dependency("org.fusesource.jansi:.*:.*"))
//        exclude(dependency("org.jline:jline-terminal-jansi:.*"))
//        // Leave SLF4J in place, or else a warning is spit out
//        exclude(dependency("org.slf4j.*:.*:.*"))
//    }
//}

distributions {
    named("shadow") {
        // We choose to make the output names of "assembleShadowDist" the same as "assembleDist" here, since ideally
        // they should be interchangeable (the shadow version just has dead code removed). However, this means if you
        // run "assembleDist" and then "assembleShadowDist" (or the other way around), the latter command will overwrite
        // the output of the prior one.
        distributionBaseName.set("kobweb")
    }
}

// Avoid ambiguity / add clarity in generated artifacts
tasks.jar {
    archiveFileName.set("kobweb-cli.jar")
}

// These values are specified in ~/.gradle/gradle.properties; otherwise sorry, no jreleasing for you :P
val (githubUsername, githubToken) = listOf("varabyte.github.username", "varabyte.github.token")
    .map { key -> findProperty(key) as? String }

if (githubUsername != null && githubToken != null) {
    // Read about JReleaser at https://jreleaser.org/guide/latest/index.html
    jreleaser {
        val isDryRun = (findProperty("kobweb.cli.jreleaser.dryrun") as? String)?.toBoolean() ?: true
        dryrun.set(isDryRun) // Specified explicitly for convenience - set dryrun to false when ready to publish!
        gitRootSearch.set(true)
        dependsOnAssemble.set(false) // We pre-assemble ourselves (using shadow jar)

        project {
            links {
                homepage.set("https://kobweb.varabyte.com/")
                documentation.set("https://kobweb.varabyte.com/docs")
                license.set("http://www.apache.org/licenses/LICENSE-2.0")
                bugTracker.set("https://github.com/varabyte/kobweb/issues")
            }
            description.set("Set up and manage your Kobweb (Compose HTML) app")
            longDescription.set(
                """
                Kobweb CLI provides commands to handle the tedious parts of building a Kobweb (Compose HTML) app,
                including project setup and configuration.
            """.trimIndent()
            )
            vendor.set("Varabyte")
            authors.set(listOf("David Herman"))
            license.set("Apache-2.0")
            copyright.set("Copyright © 2024 Varabyte. All rights reserved.")

            // Set the Java version explicitly, even though in theory this value should be coming from our root
            // build.gradle file, but it does not seem to when I run "jreleaserPublish" from the command line.
            // See also: https://github.com/jreleaser/jreleaser/issues/785
            java {
                version.set(JavaVersion.VERSION_11.toString())
            }
        }
        release {
            github {
                repoOwner.set("varabyte")
                tagName.set("v{{projectVersion}}")
                username.set(githubUsername)
                token.set(githubToken)

                // Tags and releases are handled manually via the GitHub UI for now.
                // TODO(https://github.com/varabyte/kobweb/issues/104)
                skipTag.set(true)
                skipRelease.set(true)

                overwrite.set(true)
                uploadAssets.set(Active.RELEASE)
                commitAuthor {
                    name.set("David Herman")
                    email.set("bitspittle@gmail.com")
                }
                changelog {
                    enabled.set(false)
                }
                milestone {
                    // milestone management handled manually for now
                    close.set(false)
                }
                prerelease {
                    enabled.set(false)
                }
            }
        }
        packagers {
            brew {
                active.set(Active.RELEASE)
                templateDirectory.set(File("jreleaser/templates/brew"))
                // The following changes the line `depends_on "openjdk@11"` to `depends_on "openjdk"
                // See also: https://jreleaser.org/guide/latest/reference/packagers/homebrew.html#_jdk_dependency
                extraProperties.put("useVersionedJava", false)
            }
            scoop {
                active.set(Active.RELEASE)
            }

            val (key, token) = listOf(findProperty("sdkman.key") as? String, findProperty("sdkman.token") as? String)
            if (key != null && token != null) {
                sdkman {
                    consumerKey.set(key)
                    consumerToken.set(token)
                    active.set(Active.RELEASE)
                }
            } else {
                println("SDKMAN! packager disabled on this machine since key and/or token are not defined")
            }
        }

        distributions {
            create("kobweb") {
                listOf("zip", "tar").forEach { artifactExtension ->
                    artifact {
                        setPath("build/distributions/{{distributionName}}-{{projectVersion}}.$artifactExtension")
                    }
                }
            }
        }
    }
} else {
    println(
        """
            NOTE: JReleaser disabled for this machine due to missing github username and/or token properties.
            This is expected (unless you intentionally configured these values).
        """.trimIndent()
    )
}
