package com.varabyte.kobweb.cli.stop

import com.varabyte.kobweb.cli.common.*
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.text.textLine
import java.io.File

private enum class StopState {
    STOPPING,
    STOPPED,
}

fun handleStop(projectDir: File, useAnsi: Boolean, gradleArgs: List<String>) {
    // Server environment doesn't really matter for "stop". Still, let's default to prod because that's usually the case
    // where a server is left running for a long time.
    KobwebGradle(ServerEnvironment.PROD, projectDir).use { kobwebGradle ->
        handleStop(useAnsi, kobwebGradle, gradleArgs)
    }
}

private fun handleStop(
    useAnsi: Boolean,
    kobwebGradle: KobwebGradle,
    gradleArgs: List<String>,
) {
    var runInPlainMode = !useAnsi

    if (useAnsi && !trySession {
        val kobwebApplication = findKobwebApplication(kobwebGradle.projectDir.toPath()) ?: return@trySession
        if (kobwebApplication.isServerAlreadyRunning()) {
            newline() // Put space between user prompt and eventual first line of Gradle output

            val ellipsisAnim = textAnimOf(Anims.ELLIPSIS)
            var stopState by liveVarOf(StopState.STOPPING)
            section {
                textLine() // Add text line between this block and Gradle output above

                when (stopState) {
                    StopState.STOPPING -> {
                        textLine("Stopping a Kobweb server$ellipsisAnim")
                    }
                    StopState.STOPPED -> {
                        textLine("Server was stopped.")
                    }
                }
            }.run {
                kobwebGradle.onStarting = ::informGradleStarting
                val stopServerProcess = kobwebGradle.stopServer(gradleArgs)
                stopServerProcess.lineHandler = ::handleConsoleOutput
                stopServerProcess.waitFor()
                stopState = StopState.STOPPED
            }
        }
        else {
            section {
                textLine("Did not detect a running server.")
            }.run()
        }
    }) {
        warnFallingBackToPlainText()
        runInPlainMode = true
    }

    if (runInPlainMode) {
        val kobwebApplication = assertKobwebApplication(kobwebGradle.projectDir.toPath())
        if (!kobwebApplication.isServerAlreadyRunning()) {
            println("Did not detect a running server.")
            return
        }

        kobwebGradle.stopServer(gradleArgs).also { it.waitFor() }
    }
}
