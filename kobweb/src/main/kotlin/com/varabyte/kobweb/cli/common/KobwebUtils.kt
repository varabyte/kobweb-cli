package com.varabyte.kobweb.cli.common

import com.varabyte.kobweb.common.error.KobwebException
import com.varabyte.kobweb.project.KobwebApplication
import com.varabyte.kobweb.project.KobwebFolder
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.bold
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.Session
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import kotlin.streams.toList

/**
 * Classes needed for the CLI to be able to execute commands for the current Kobweb project.
 */
class KobwebExecutionEnvironment(
    val application: KobwebApplication,
    val gradle: KobwebGradle,
) : Closeable {
    override fun close() {
        gradle.close()
    }
}

private const val NOT_KOBWEB_APPLICATION_ERROR = "This command must be called in a Kobweb application module."

fun assertKobwebApplication(path: Path): KobwebApplication {
    return try {
        KobwebApplication(path)
    } catch (ex: KobwebException) {
        throw KobwebException(NOT_KOBWEB_APPLICATION_ERROR)
    }
}

fun assertKobwebExecutionEnvironment(env: ServerEnvironment, path: Path): KobwebExecutionEnvironment {
    return try {
        KobwebExecutionEnvironment(
            KobwebApplication(path),
            KobwebGradle(env, path),
        )
    } catch (ex: KobwebException) {
        throw KobwebException(NOT_KOBWEB_APPLICATION_ERROR)
    }
}

fun KobwebFolder.assertServerNotAlreadyRunning() {
    ServerStateFile(this).content?.let { serverState ->
        if (serverState.isRunning()) {
            throw KobwebException("Cannot execute this command as a server is already running (PID=${serverState.pid}). Consider running `kobweb stop` if this is unexpected.")
        }
    }
}

fun KobwebApplication.assertServerNotAlreadyRunning() {
    this.kobwebFolder.assertServerNotAlreadyRunning()
}

fun KobwebApplication.isServerAlreadyRunning(): Boolean {
    return try {
        assertServerNotAlreadyRunning()
        false
    } catch (ex: KobwebException) {
        true
    }
}

fun Session.findKobwebApplication(path: Path): KobwebApplication? {
    val foundPath: Path? = if (!KobwebFolder.isFoundIn(path)) {
        val candidates = try {
            Files.walk(path, 2)
                .filter(Files::isDirectory)
                .filter { KobwebFolder.isFoundIn(it) }
                .toList()
        } catch(ex: Exception) {
            // If this happens, we definitely don't have access to projects to recommend to users.
            // The user is probably running kobweb in a privileged location, perhaps.
            emptyList()
        }

        if (candidates.isNotEmpty()) {
            if (candidates.size == 1) {
                val candidate = candidates.single()

                var shouldUseNewLocation by liveVarOf(true)
                section {
                    textLine()
                    textInfoPrefix()
                    text("A Kobweb application was not found here, but one was found in ")
                    cyan { text(candidate.relativeTo(path).toString()) }
                    textLine(".")
                    textLine()

                    text("Use ")
                    cyan { text(candidate.relativeTo(path).toString()) }
                    text(" instead? ")

                    bold {
                        if (shouldUseNewLocation) {
                            textLine("[Yes] No ")
                        } else {
                            textLine(" Yes [No]")
                        }
                    }
                    textLine()
                }.runUntilSignal {
                    onKeyPressed {
                        when (key) {
                            Keys.LEFT, Keys.RIGHT -> shouldUseNewLocation = !shouldUseNewLocation
                            Keys.HOME -> shouldUseNewLocation = true
                            Keys.END -> shouldUseNewLocation = false
                            // Q included because Kobweb users might be used to pressing it in other contexts
                            Keys.ESC, Keys.Q, Keys.Q_UPPER -> {
                                shouldUseNewLocation = false; signal()
                            }

                            Keys.Y, Keys.Y_UPPER -> {
                                shouldUseNewLocation = true; signal()
                            }

                            Keys.N, Keys.N_UPPER -> {
                                shouldUseNewLocation = false; signal()
                            }

                            Keys.ENTER -> signal()
                        }
                    }
                }

                candidate.takeIf { shouldUseNewLocation }
            } else {

                var candidateIndex by liveVarOf(0)
                var shouldUseNewLocation by liveVarOf(true)

                section {
                    textLine()
                    textInfoPrefix()
                    textLine("Multiple Kobweb applications were found under the current directory. Choose one or press ESC to cancel.")
                    textLine()
                    candidates.forEachIndexed { index, candidate ->
                        text(if (index == candidateIndex) '>' else ' ')
                        text(' ')
                        cyan { textLine(candidate.relativeTo(path).toString()) }
                    }
                    textLine()
                }.runUntilSignal {
                    onKeyPressed {
                        when (key) {
                            Keys.UP -> candidateIndex =
                                (candidateIndex - 1).let { if (it < 0) candidates.size - 1 else it }

                            Keys.DOWN -> candidateIndex = (candidateIndex + 1) % candidates.size
                            Keys.HOME -> candidateIndex = 0
                            Keys.END -> candidateIndex = candidates.size - 1
                            // Q included because Kobweb users might be used to pressing it in other contexts
                            Keys.ESC, Keys.Q, Keys.Q_UPPER -> {
                                shouldUseNewLocation = false; signal()
                            }

                            Keys.ENTER -> signal()
                        }
                    }
                }

                candidates[candidateIndex].takeIf { shouldUseNewLocation }
            }
        } else {
            null
        }
    } else {
        path
    }

    val foundApplication = try {
        foundPath?.let { KobwebApplication(it) }?.also { application ->
            if (application.path != path) {
                informInfo {
                    text("Running as ")
                    cyan { text("kobweb run -p ${application.path.relativeTo(path)}") }
                }
            }
        }
    } catch (ex: KobwebException) {
        null
    }

    if (foundApplication == null) {
        informError(NOT_KOBWEB_APPLICATION_ERROR)
    }
    return foundApplication
}

// If `useAnsi` is true, this must NOT be called within an existing Kotter session! Because it will try to create a
// new one.
fun findKobwebExecutionEnvironment(env: ServerEnvironment, root: Path, useAnsi: Boolean): KobwebExecutionEnvironment? {
    return if (useAnsi) {
        var application: KobwebApplication? = null
        session {
            application = findKobwebApplication(root)
        }
        application?.let { KobwebExecutionEnvironment(it, KobwebGradle(env, it.path)) }
    } else {
        assertKobwebExecutionEnvironment(env, root)
    }
}


fun Session.isServerAlreadyRunningFor(project: KobwebApplication): Boolean {
    return try {
        project.assertServerNotAlreadyRunning()
        false
    } catch (ex: KobwebException) {
        newline()
        informError(ex.message!!)
        true
    }
}

fun Session.showStaticSiteLayoutWarning() {
    section {
        // TODO(#123): Link to URL doc link when available.
        yellow { textLine("Static site layout chosen. Some Kobweb features like server api routes / api streams are unavailable in this configuration.") }
        textLine()
    }.run()
}
