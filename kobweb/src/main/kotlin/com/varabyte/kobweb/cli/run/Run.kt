package com.varabyte.kobweb.cli.run

import com.github.ajalt.clikt.core.CliktError
import com.varabyte.kobweb.cli.common.Anims
import com.varabyte.kobweb.cli.common.GradleAlertBundle
import com.varabyte.kobweb.cli.common.KobwebExecutionEnvironment
import com.varabyte.kobweb.cli.common.assertServerNotAlreadyRunning
import com.varabyte.kobweb.cli.common.findKobwebConfIn
import com.varabyte.kobweb.cli.common.findKobwebExecutionEnvironment
import com.varabyte.kobweb.cli.common.handleGradleOutput
import com.varabyte.kobweb.cli.common.isServerAlreadyRunningFor
import com.varabyte.kobweb.cli.common.kotter.handleConsoleOutput
import com.varabyte.kobweb.cli.common.kotter.informGradleStarting
import com.varabyte.kobweb.cli.common.kotter.newline
import com.varabyte.kobweb.cli.common.kotter.textInfo
import com.varabyte.kobweb.cli.common.kotter.trySession
import com.varabyte.kobweb.cli.common.kotter.warn
import com.varabyte.kobweb.cli.common.kotter.warnFallingBackToPlainText
import com.varabyte.kobweb.cli.common.showStaticSiteLayoutWarning
import com.varabyte.kobweb.cli.common.waitForAndCheckForException
import com.varabyte.kobweb.cli.stop.handleStop
import com.varabyte.kobweb.common.navigation.BasePath
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kobweb.server.api.ServerRequest
import com.varabyte.kobweb.server.api.ServerRequestsFile
import com.varabyte.kobweb.server.api.ServerState
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kobweb.server.api.SiteLayout
import com.varabyte.kotter.foundation.anim.Anim
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.anim.textLine
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.shutdown.addShutdownHook
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.foundation.timer.addTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    gradleArgsCommon: List<String>,
    gradleArgsStart: List<String>,
    gradleArgsStop: List<String>,
) {
    val kobwebExecutionEnvironment = findKobwebExecutionEnvironment(env, projectDir.toPath(), useAnsi)
        ?: return // Error message already printed

    kobwebExecutionEnvironment.use {
        handleRun(
            env,
            siteLayout,
            useAnsi,
            runInForeground,
            kobwebExecutionEnvironment,
            gradleArgsCommon,
            gradleArgsStart,
            gradleArgsStop,
        )
    }
}

private fun handleRun(
    env: ServerEnvironment,
    siteLayout: SiteLayout,
    useAnsi: Boolean,
    runInForeground: Boolean,
    kobwebExecutionEnvironment: KobwebExecutionEnvironment,
    gradleArgsCommon: List<String>,
    gradleArgsStart: List<String>,
    gradleArgsStop: List<String>,
) {
    val kobwebApplication = kobwebExecutionEnvironment.application
    val kobwebGradle = kobwebExecutionEnvironment.gradle

    var runInPlainMode = !useAnsi
    if (useAnsi && !trySession {
            if (runInForeground) {
                warn("User requested running in foreground mode, which will be ignored in interactive mode.")
            }

            if (isServerAlreadyRunningFor(kobwebApplication, kobwebGradle)) return@trySession

            val kobwebFolder = kobwebApplication.kobwebFolder
            val conf = findKobwebConfIn(kobwebFolder) ?: return@trySession

            newline() // Put space between user prompt and eventual first line of Gradle output

            if (siteLayout.isStatic) {
                showStaticSiteLayoutWarning()
            }

            val envName = when (env) {
                ServerEnvironment.DEV -> "development"
                ServerEnvironment.PROD -> "production"
            }
            val serverStateFile = ServerStateFile(kobwebFolder)

            val gradleAlertBundle = GradleAlertBundle(this)
            var userRequestedCancelWhileBuilding = false

            run {
                val ellipsisAnim = textAnimOf(Anims.ELLIPSIS)
                var runState by liveVarOf(RunState.STARTING)
                var serverState: ServerState? = null // Set on and after RunState.RUNNING
                var cancelReason by liveVarOf("")
                var exception by liveVarOf<Exception?>(null) // Set if RunState.INTERRUPTED
                // If a base path is set, we'll add it to the server URL (at which point we'll need to add slash dividers)
                val basePath = BasePath(conf.site.basePathOrRoutePrefix)
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
                                    cyan { text("http://localhost:${serverState.port}$basePath") }
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
                        kobwebGradle.startServer(
                            enableLiveReloading = (env == ServerEnvironment.DEV),
                            siteLayout,
                            gradleArgsCommon + gradleArgsStart,
                        )
                    } catch (ex: Exception) {
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
                        if (key in listOf(Keys.EOF, Keys.Q, Keys.Q_UPPER)) {
                            if (runState == RunState.STARTING) {
                                runState = RunState.STOPPING
                                CoroutineScope(Dispatchers.IO).launch {
                                    startServerProcess.cancel()
                                    startServerProcess.waitFor()
                                    cancelReason = "User quit before server could confirm it had started up."
                                    runState = RunState.CANCELLED
                                    userRequestedCancelWhileBuilding = true
                                    signal()
                                }
                            } else if (runState == RunState.RUNNING) {
                                runState = RunState.STOPPING
                                CoroutineScope(Dispatchers.IO).launch {
                                    startServerProcess.cancel()
                                    startServerProcess.waitFor()

                                    val stopServerProcess = kobwebGradle.stopServer(gradleArgsCommon + gradleArgsStop)
                                    stopServerProcess.lineHandler = ::handleConsoleOutput
                                    stopServerProcess.waitFor()

                                    runState = RunState.STOPPED
                                    signal()
                                }
                            }
                        } else {
                            gradleAlertBundle.handleKey(key)
                        }
                    }

                    coroutineScope {
                        while (runState == RunState.STARTING) {
                            serverStateFile.content?.takeIf { it.isRunning() }?.let {
                                serverState = it
                                runState = RunState.RUNNING
                            } ?: run { delay(300) }
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
            }

            run {
                var runningServerDetected by liveVarOf(false)
                var checkAborted by liveVarOf(false)
                // Only wait for a running server if at least one task has run. If the user cancelled their run before
                // that early, the chance of a server starting is zero.
                if (userRequestedCancelWhileBuilding && gradleAlertBundle.hasFirstTaskRun) {
                    var remainingTimeMs by liveVarOf(5000) // In practice, we usually detect a server within 2 seconds.

                    fun Int.msToSecTimeString() = "${this / 1000}.${(this % 1000).toString().padEnd(3, '0')}s"

                    section {
                        textLine()
                        if (!runningServerDetected) {
                            textLine("Depending on timing, a server might still start up despite a cancellation request.")
                            textLine()
                            if (checkAborted) {
                                text("Check cancelled. Consider running ")
                                cyan { text("kobweb stop") }
                                textLine(" later to verify that no server started up.")
                            } else if (remainingTimeMs > 0) {
                                textLine("Watching for a Kobweb server. (Remaining: ${remainingTimeMs.msToSecTimeString()})")
                                textLine()
                                textLine("Press any key to abort this check.")
                            } else {
                                textLine("Server startup was successfully cancelled.")
                            }
                        } else {
                            textInfo("Running server detected after cancellation request. Shutting it down.")
                        }
                        textLine()
                    }.runUntilSignal {
                        addTimer(Anim.ONE_FRAME_60FPS, repeat = true) {
                            remainingTimeMs -= elapsed.inWholeMilliseconds.toInt()
                            if (remainingTimeMs < 0) remainingTimeMs = 0

                             runningServerDetected = serverStateFile.content?.isRunning() == true

                            if (remainingTimeMs == 0 || runningServerDetected) {
                                repeat = false
                                signal()
                            }
                        }

                        onKeyPressed {
                            checkAborted = true
                            signal()
                        }
                    }
                }

                if (runningServerDetected) {
                    handleStop(kobwebGradle)
                }
            }
        }) {
        warnFallingBackToPlainText()
        runInPlainMode = true
    }

    if (runInPlainMode) {
        kobwebApplication.assertServerNotAlreadyRunning()

        if (gradleArgsStop.isNotEmpty()) {
            println("Warning: --gradle-stop is ignored when running in non-interactive mode (which does not stop the server).")
        }

        // If we're non-interactive, it means we just want to start the Kobweb server and exit without waiting for
        // for any additional changes. (This is essentially used when run in a web server environment)
        val runFailed = kobwebGradle
            .startServer(enableLiveReloading = false, siteLayout, gradleArgsCommon + gradleArgsStart)
            .waitForAndCheckForException() != null
        if (runFailed) {
            throw CliktError("Failed to start a Kobweb server. Please check Gradle output and resolve any errors before retrying.")
        }

        val serverStateFile = ServerStateFile(kobwebApplication.kobwebFolder)
        runBlocking {
            while (serverStateFile.content?.isRunning() == false) {
                delay(20) // Low delay because startup should happen fairly quickly
            }
        }

        if (runInForeground) {
            println()
            println("Press CTRL-C to exit this application and shutdown the server.")
            Runtime.getRuntime().addShutdownHook(Thread {
                if (serverStateFile.content?.isRunning() == false) return@Thread
                ServerRequestsFile(kobwebApplication.kobwebFolder).enqueueRequest(ServerRequest.Stop())
                println()
                println("CTRL-C received. Sent a message to stop the server.")
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
