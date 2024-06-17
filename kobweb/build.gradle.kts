import org.jreleaser.model.Active
import java.io.FileNotFoundException
import java.nio.file.Paths
import java.util.jar.JarFile

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
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotter)
    implementation(libs.freemarker)
    implementation(libs.kaml)
    implementation(libs.okhttp)
    implementation(libs.kobweb.common)

    // For Gradle Tooling API (used for starting up / communicating with a gradle daemon)
    implementation("org.gradle:gradle-tooling-api:${gradle.gradleVersion}")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.6") // Needed by gradle tooling
}

application {
    applicationDefaultJvmArgs = listOf("-Dkobweb.version=${version}")
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

// Proguard for minimizing the JAR file
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath(libs.plugins.proguard.get().toString()) {
            // On older versions of proguard, Android build tools will be included
            exclude("com.android.tools.build")
        }
    }
}

tasks.register<proguard.gradle.ProGuardTask>("proguard") {
    dependsOn(tasks.shadowJar)

    val originalJarFile = tasks.shadowJar.flatMap { it.archiveFile }
    val originalJarFileNameWithoutExtension = originalJarFile.get().asFile.nameWithoutExtension
    val originalJarFileDestination = tasks.shadowJar.flatMap { it.destinationDirectory }

    val minimizedJarFile = originalJarFileDestination.get().file("$originalJarFileNameWithoutExtension-min.jar")

    injars(originalJarFile)
    outjars(minimizedJarFile)

    val javaHome = System.getProperty("java.home")

    // Starting from Java 9, runtime classes are packaged in modular JMOD files.
    fun includeModuleFromJdk(jModFileNameWithoutExtension: String) {
        val jModFilePath = Paths.get(javaHome, "jmods", "$jModFileNameWithoutExtension.jmod").toString()
        val jModFile = File(jModFilePath)
        if (!jModFile.exists()) {
            throw FileNotFoundException("The '$jModFileNameWithoutExtension' at '$jModFilePath' doesn't exist.")
        }
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            jModFilePath,
        )
    }

    val javaModules =
        listOf(
            "java.base",
            // Needed to support Java Swing/Desktop (Required by Kotter Virtual Terminal)
            "java.desktop",
            // Java Data Transfer is required by Kotter Virtual Terminal
            "java.datatransfer",
            // Needed to support Java logging utils (required by Okio)
            "java.logging",
            // Java RMI is required by freemarker and some other dependencies
            "java.rmi",
            // Java XML is required by freemarker and some other dependencies
            "java.xml",
            // Java SQL is required by freemarker and some other dependencies
            "java.sql",
        )
    javaModules.forEach { includeModuleFromJdk(jModFileNameWithoutExtension = it) }

    // Includes the main source set's compile classpath for Proguard.
    // Notice that Shadow JAR already includes Kotlin standard library and dependencies, yet this
    // is essential for resolving Kotlin and other library warnings without using '-dontwarn kotlin.**'
    injars(sourceSets.main.get().compileClasspath)

    printmapping(originalJarFileDestination.get().file("$originalJarFileNameWithoutExtension.map"))

    // Disabling obfuscation makes the JAR file size a bit bigger and makes the debugging process a bit less easy
    dontobfuscate()
    // Kotlinx serialization breaks when using optimizations
    dontoptimize()

    configuration(file("proguard.pro"))

    // Use Proguard rules that provided by dependencies in JAR file
    doFirst {
        JarFile(originalJarFile.get().asFile).use { jarFile ->
            val generatedRulesFiles =
                jarFile.entries().asSequence()
                    .filter { it.name.startsWith("META-INF/proguard") && !it.isDirectory }
                    .map { entry ->
                        jarFile.getInputStream(entry).bufferedReader().use { reader ->
                            Pair(reader.readText(), entry)
                        }
                    }
                    .toList()

            val buildProguardDirectory = layout.buildDirectory.dir("proguard").get().asFile
            if (!buildProguardDirectory.exists()) {
                buildProguardDirectory.mkdir()
            }
            generatedRulesFiles.forEach { (rulesContent, rulesFileEntry) ->
                val rulesFileNameWithExtension = rulesFileEntry.name.substringAfterLast("/")
                val generatedProguardFile = File(buildProguardDirectory, "generated-$rulesFileNameWithExtension")
                if (!generatedProguardFile.exists()) {
                    generatedProguardFile.createNewFile()
                }
                generatedProguardFile.bufferedWriter().use { bufferedWriter ->
                    bufferedWriter.appendLine("# Generated file from ($rulesFileEntry) - manual changes will be overwritten")
                    bufferedWriter.appendLine()

                    bufferedWriter.appendLine(rulesContent)
                }

                configuration(generatedProguardFile)
            }
        }
    }
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
