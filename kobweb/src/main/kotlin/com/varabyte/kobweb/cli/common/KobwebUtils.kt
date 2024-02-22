package com.varabyte.kobweb.cli.common

import com.varabyte.kobweb.cli.common.kotter.informError
import com.varabyte.kobweb.cli.common.kotter.informInfo
import com.varabyte.kobweb.cli.common.kotter.newline
import com.varabyte.kobweb.cli.common.kotter.onYesNoChanged
import com.varabyte.kobweb.cli.common.kotter.textInfoPrefix
import com.varabyte.kobweb.cli.common.kotter.yesNo
import com.varabyte.kobweb.common.error.KobwebException
import com.varabyte.kobweb.project.KobwebApplication
import com.varabyte.kobweb.project.KobwebFolder
import com.varabyte.kobweb.project.conf.KobwebConf
import com.varabyte.kobweb.project.conf.KobwebConfFile
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.Session
import java.io.Closeable
import java.nio.file.Path
import kotlin.io.path.absolutePathString

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

fun assertKobwebConfIn(kobwebFolder: KobwebFolder): KobwebConf {
    return KobwebConfFile(kobwebFolder).content
        ?: throw KobwebException("A .kobweb folder's `conf.yaml` file seems to have been deleted at some point. This is not expected and Kobweb cannot run without it. Consider restoring your `conf.yaml` file from source control history if possible, or create a new, temporary Kobweb project from scratch and copy its `conf.yaml` file over, modifying it as necessary.")
}

fun assertKobwebExecutionEnvironment(env: ServerEnvironment, path: Path): KobwebExecutionEnvironment {
    return KobwebExecutionEnvironment(
        assertKobwebApplication(path),
        KobwebGradle(env, path),
    )
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

fun Session.findKobwebApplication(basePath: Path): KobwebApplication? {
    // When we report the location of the target path to the user, we want to do it relative from where the user ran the
    // kobweb command from. But this might fail (e.g. on Windows running the command on one drive targeting another),
    // so in that case, just append the "this" path to the base path, because within the context of this function,
    // `this` will always be a child of `basePath`.
    fun Path.relativeToCurrentDirectoryOrBasePath(): Path {
        check(this.absolutePathString().startsWith(basePath.absolutePathString()))
        return this.relativeToCurrentDirectory() ?: basePath.resolve(this)
    }


    val foundPath: Path? = if (!KobwebFolder.isFoundIn(basePath)) {
        val candidates = try {
            basePath.toFile().walkTopDown().maxDepth(2)
                .filter { it.isDirectory && KobwebFolder.isFoundIn(it.toPath()) }
                .map { it.toPath() }
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
                    cyan { text(candidate.relativeToCurrentDirectoryOrBasePath().toString()) }
                    textLine(".")
                    textLine()

                    text("Use ")
                    cyan { text(candidate.relativeToCurrentDirectoryOrBasePath().toString()) }
                    text(" instead? ")

                    yesNo(shouldUseNewLocation)
                    textLine()
                }.runUntilSignal {
                    onYesNoChanged {
                        shouldUseNewLocation = isYes
                        if (shouldAccept) signal()
                    }
                }

                candidate.takeIf { shouldUseNewLocation }
            } else {

                var candidateIndex by liveVarOf(0)
                var shouldUseNewLocation by liveVarOf(true)

                section {
                    textLine()
                    textInfoPrefix()
                    textLine("A Kobweb application was not found here, but multiple Kobweb applications were found in nested folders. Choose one or press Q to cancel.")
                    textLine()
                    candidates.forEachIndexed { index, candidate ->
                        text(if (index == candidateIndex) '>' else ' ')
                        text(' ')
                        cyan { textLine(candidate.relativeToCurrentDirectoryOrBasePath().toString()) }
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
        basePath
    }

    val foundApplication = try {
        foundPath?.let { KobwebApplication(it) }?.also { application ->
            if (application.path != basePath) {
                informInfo {
                    val argsCopy = Globals.getValue(ProgramArgsKey).toMutableList()
                    val pathIndex = argsCopy.indexOfFirst { it == "-p" || it == "--path" }
                    val newPath = application.path.relativeToCurrentDirectoryOrBasePath().toString()
                    when {
                        // Replace over the old path
                        pathIndex >= 0 -> argsCopy[pathIndex + 1] = newPath
                        // Add "-p <newPath>". Always set it as the first argument after the subcommand,
                        // e.g. `kobweb run --env prod` -> `kobweb run -p <newPath> --env prod`
                        // to make the change easier to see for the user but also to reduce the chance of this causing a
                        // problem in the future (e.g. if we add an argument that consumes the rest of the line or
                        // something)
                        else -> argsCopy.addAll(1, listOf("-p", newPath))
                    }

                    text("Running: ")
                    cyan { text("kobweb ${argsCopy.joinToString(" ")}") }
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

fun Session.findKobwebConfIn(kobwebFolder: KobwebFolder): KobwebConf? {
    return try {
        assertKobwebConfIn(kobwebFolder)
    } catch (ex: KobwebException) {
        informError(ex.message!!)
        null
    }
}

fun Session.findKobwebConfFor(kobwebApplication: KobwebApplication) = findKobwebConfIn(kobwebApplication.kobwebFolder)

fun Session.showStaticSiteLayoutWarning() {
    section {
        // TODO(#123): Link to URL doc link when available.
        yellow { textLine("Static site layout chosen. Some Kobweb features like server api routes / api streams are unavailable in this configuration.") }
        textLine()
    }.run()
}
