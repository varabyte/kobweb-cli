package com.varabyte.kobweb.cli.stop

import com.github.ajalt.clikt.core.CliktError
import com.varabyte.kobweb.cli.common.Anims
import com.varabyte.kobweb.cli.common.KobwebExecutionEnvironment
import com.varabyte.kobweb.cli.common.KobwebGradle
import com.varabyte.kobweb.cli.common.findKobwebExecutionEnvironment
import com.varabyte.kobweb.cli.common.isServerAlreadyRunning
import com.varabyte.kobweb.cli.common.kotter.handleConsoleOutput
import com.varabyte.kobweb.cli.common.kotter.informGradleStarting
import com.varabyte.kobweb.cli.common.kotter.newline
import com.varabyte.kobweb.cli.common.kotter.trySession
import com.varabyte.kobweb.cli.common.kotter.warnFallingBackToPlainText
import com.varabyte.kobweb.cli.common.waitForAndCheckForException
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.Session
import java.io.File

private enum class StopState {
    STOPPING,
    STOPPED,
}

fun handleStop(projectDir: File, useAnsi: Boolean, gradleArgsCommon: List<String>, gradleArgsStop: List<String>) {
    // Server environment doesn't really matter for "stop". Still, let's default to prod because that's usually the case
    // where a server is left running for a long time.
    findKobwebExecutionEnvironment(
        ServerEnvironment.PROD,
        projectDir.toPath(),
        useAnsi
    )?.use { kobwebExecutionEnvironment ->
        handleStop(useAnsi, kobwebExecutionEnvironment, gradleArgsCommon, gradleArgsStop)
    }
}

fun Session.handleStop(kobwebGradle: KobwebGradle) = handleStop(kobwebGradle, emptyList(), emptyList())

fun Session.handleStop(
    kobwebGradle: KobwebGradle,
    gradleArgsCommon: List<String>,
    gradleArgsStop: List<String>
) {
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
        val stopServerProcess = kobwebGradle.stopServer(gradleArgsCommon + gradleArgsStop)
        stopServerProcess.lineHandler = ::handleConsoleOutput
        stopServerProcess.waitFor()
        stopState = StopState.STOPPED
    }
}

private fun handleStop(
    useAnsi: Boolean,
    kobwebExecutionEnvironment: KobwebExecutionEnvironment,
    gradleArgsCommon: List<String>,
    gradleArgsStop: List<String>,
) {
    var runInPlainMode = !useAnsi
    val kobwebApplication = kobwebExecutionEnvironment.application
    val kobwebGradle = kobwebExecutionEnvironment.gradle

    if (useAnsi && !trySession {
            if (kobwebApplication.isServerAlreadyRunning()) {
                newline() // Put space between user prompt and eventual first line of Gradle output
                handleStop(kobwebGradle, gradleArgsCommon, gradleArgsStop)
            } else {
                section {
                    textLine()
                    textLine("Did not detect a running server.")
                }.run()
            }
        }) {
        warnFallingBackToPlainText()
        runInPlainMode = true
    }

    if (runInPlainMode) {
        if (!kobwebApplication.isServerAlreadyRunning()) {
            println("Did not detect a running server.")
            return
        }

        val stopFailed = kobwebGradle
            .stopServer(gradleArgsCommon + gradleArgsStop)
            .waitForAndCheckForException() != null

        if (stopFailed) {
            throw CliktError("Failed to stop a Kobweb server. Please check Gradle output and resolve any errors before retrying.")
        }
    }
}
