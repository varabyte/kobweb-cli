package com.varabyte.kobweb.cli.run

import com.varabyte.kobweb.cli.common.*
import com.varabyte.kobweb.common.navigation.RoutePrefix
import com.varabyte.kobweb.project.conf.KobwebConfFile
import com.varabyte.kobweb.server.api.SiteLayout
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kobweb.server.api.ServerRequest
import com.varabyte.kobweb.server.api.ServerRequestsFile
import com.varabyte.kobweb.server.api.ServerState
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.anim.textLine
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.shutdown.addShutdownHook
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.foundation.timer.addTimer
import kotlinx.coroutines.*
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private enum class RunState {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    CANCELLING,
    CANCELLED,
    INTERRUPTED,
}

fun handleRun(
    env: ServerEnvironment,
    projectDir: File,
    siteLayout: SiteLayout,
    useAnsi: Boolean,
    runInForeground: Boolean,
    gradleArgs: List<String>,
) {
    val originalEnv = env

    @Suppress("NAME_SHADOWING") // We're intentionally intercepting the original value
    val env = env.takeIf { siteLayout != SiteLayout.STATIC } ?: ServerEnvironment.PROD
    KobwebGradle(env, projectDir).use { kobwebGradle -> handleRun(originalEnv, env, siteLayout, useAnsi, runInForeground, kobwebGradle, gradleArgs) }
}

private fun handleRun(
    originalEnv: ServerEnvironment,
    env: ServerEnvironment,
    siteLayout: SiteLayout,
    useAnsi: Boolean,
    runInForeground: Boolean,
    kobwebGradle: KobwebGradle,
    gradleArgs: List<String>,
) {
    var runInPlainMode = !useAnsi
    if (useAnsi && !trySession {
        if (runInForeground) {
            warn("User requested running in foreground mode, which will be ignored in interactive mode.")
        }

        val kobwebApplication = findKobwebApplication(kobwebGradle.projectDir.toPath()) ?: return@trySession
        if (isServerAlreadyRunningFor(kobwebApplication)) return@trySession

        val kobwebFolder = kobwebApplication.kobwebFolder
        val conf = KobwebConfFile(kobwebFolder).content!!

        newline() // Put space between user prompt and eventual first line of Gradle output

        if (siteLayout == SiteLayout.STATIC) {
            showStaticSiteLayoutWarning()

            if (originalEnv == ServerEnvironment.DEV) {
                section {
                    // Brighten the color to contrast with the warning above
                    yellow(isBright = true) {
                        textLine(
                            """
                            Note: Development mode is not designed to work with static layouts, so the
                            server will run in production mode instead.

                            To avoid seeing this message, use `--env prod` explicitly.
                            """.trimIndent()
                        )
                    }
                    textLine()
                }.run()
            }
        }

        val envName = when (env) {
            ServerEnvironment.DEV -> "development"
            ServerEnvironment.PROD -> "production"
        }
        var serverState: ServerState? = null // Set on and after RunState.RUNNING
        val ellipsisAnim = textAnimOf(Anims.ELLIPSIS)
        var runState by liveVarOf(RunState.STARTING)
        var cancelReason by liveVarOf("")
        var exception by liveVarOf<Exception?>(null) // Set if RunState.INTERRUPTED
        val gradleAlertBundle = GradleAlertBundle(this)
        // If a route prefix is set, we'll add it to the server URL (at which point we'll need to add slash dividers)
        val routePrefix = RoutePrefix(conf.site.routePrefix)
        section {
            textLine() // Add text line between this block and Gradle output above

            when (runState) {
                RunState.STARTING -> {
                    textLine("Starting a Kobweb server ($envName)$ellipsisAnim")
                    textLine()
                    gradleAlertBundle.renderInto(this)
                    textLine("Press Q anytime to cancel.")
                }
                RunState.RUNNING -> {
                    serverState!!.let { serverState ->
                        green {
                            text("Kobweb server ($envName) is running at ")
                            cyan { text("http://localhost:${serverState.port}$routePrefix") }
                        }
                        textLine(" (PID = ${serverState.pid})")
                        textLine()
                        gradleAlertBundle.renderInto(this)
                        textLine("Press Q anytime to stop the server.")
                    }
                }
                RunState.STOPPING -> {
                    text("Server is stopping")
                    serverState?.let { serverState ->
                        text(" (PID = ${serverState.pid})")
                    }
                    textLine(ellipsisAnim)
                }
                RunState.STOPPED -> {
                    textLine("Server was stopped.")
                }
                RunState.CANCELLING -> {
                    check(cancelReason.isNotBlank())
                    yellow { textLine("Cancelling: $cancelReason$ellipsisAnim") }
                }
                RunState.CANCELLED -> {
                    yellow { textLine("Cancelled: $cancelReason") }
                }
                RunState.INTERRUPTED -> {
                    red { textLine("Interrupted by exception:") }
                    textLine()
                    textLine(exception!!.stackTraceToString())
                }
            }
        }.runUntilSignal {
            kobwebGradle.onStarting = ::informGradleStarting
            val startServerProcess = try {
                kobwebGradle.startServer(enableLiveReloading = (env == ServerEnvironment.DEV), siteLayout, gradleArgs)
            }
            catch (ex: Exception) {
                exception = ex
                runState = RunState.INTERRUPTED
                return@runUntilSignal
            }
            startServerProcess.lineHandler = { line, isError ->
                handleGradleOutput(line, isError) { alert -> gradleAlertBundle.handleAlert(alert) }
            }

            addShutdownHook {
                if (runState == RunState.RUNNING || runState == RunState.STOPPING) {
                    cancelReason =
                        "CTRL-C received. We kicked off a request to stop the server but we have to exit NOW before waiting for a confirmation."
                    runState = RunState.CANCELLED

                    ServerRequestsFile(kobwebFolder).enqueueRequest(ServerRequest.Stop())
                } else {
                    cancelReason = "CTRL-C received. Server startup cancelled."
                    runState = RunState.CANCELLED
                }
                signal()
            }

            onKeyPressed {
                if (key in listOf(Keys.EOF, Keys.Q)) {
                    if (runState == RunState.STARTING) {
                        runState = RunState.STOPPING
                        CoroutineScope(Dispatchers.IO).launch {
                            startServerProcess.cancel()
                            startServerProcess.waitFor()

                            cancelReason = "User quit before server could finish starting up"
                            runState = RunState.CANCELLED
                            signal()
                        }
                    } else if (runState == RunState.RUNNING) {
                        runState = RunState.STOPPING
                        CoroutineScope(Dispatchers.IO).launch {
                            startServerProcess.cancel()
                            startServerProcess.waitFor()

                            val stopServerProcess = kobwebGradle.stopServer()
                            stopServerProcess.lineHandler = ::handleConsoleOutput
                            stopServerProcess.waitFor()

                            runState = RunState.STOPPED
                            signal()
                        }
                    }
                }
                else {
                    gradleAlertBundle.handleKey(key)
                }
            }

            val serverStateFile = ServerStateFile(kobwebFolder)
            coroutineScope {
                while (runState == RunState.STARTING) {
                    serverStateFile.content?.takeIf { it.isRunning() }?.let {
                        serverState = it
                        runState = RunState.RUNNING
                        return@coroutineScope
                    }
                    delay(300)
                }
            }

            if (runState == RunState.RUNNING) {
                addTimer(500.milliseconds, repeat = true) {
                    if (runState == RunState.RUNNING) {
                        serverState!!.let { serverState ->
                            if (!serverState.isRunning() || serverStateFile.content != serverState) {
                                cancelReason = "It seems like the server was stopped by a separate process."
                                runState = RunState.CANCELLED
                                signal()
                            }
                        }
                    } else {
                        repeat = false
                    }
                }
            }
        }
    }) {
        warnFallingBackToPlainText()
        runInPlainMode = true
    }

    if (runInPlainMode) {
        val kobwebApplication = assertKobwebApplication(kobwebGradle.projectDir.toPath())
            .also { it.assertServerNotAlreadyRunning() }

        // If we're non-interactive, it means we just want to start the Kobweb server and exit without waiting for
        // for any additional changes. (This is essentially used when run in a web server environment)
        kobwebGradle.startServer(enableLiveReloading = false, siteLayout, gradleArgs).also { it.waitFor() }

        val serverStateFile = ServerStateFile(kobwebApplication.kobwebFolder)
        runBlocking {
            while (serverStateFile.content?.isRunning() == false) {
                delay(20) // Low delay because startup should happen fairly quickly
            }
        }

        if (runInForeground) {
            Runtime.getRuntime().addShutdownHook(Thread {
                if (serverStateFile.content?.isRunning() == false) return@Thread
                ServerRequestsFile(kobwebApplication.kobwebFolder).enqueueRequest(ServerRequest.Stop())
                println(); println()
                println("CTRL-C received. Sending a message to kill the server.")
                println("You may still have to run 'kobweb stop' if it didn't work.")
                System.out.flush()
            })

            runBlocking {
                while (serverStateFile.content?.isRunning() == true) {
                    delay(300)
                }
            }
        }
    }
}
