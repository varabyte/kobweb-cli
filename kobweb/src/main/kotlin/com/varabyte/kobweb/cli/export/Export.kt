package com.varabyte.kobweb.cli.export

import com.github.ajalt.clikt.core.CliktError
import com.varabyte.kobweb.cli.common.Anims
import com.varabyte.kobweb.cli.common.Globals
import com.varabyte.kobweb.cli.common.GradleAlertBundle
import com.varabyte.kobweb.cli.common.KobwebExecutionEnvironment
import com.varabyte.kobweb.cli.common.ProgramArgsKey
import com.varabyte.kobweb.cli.common.assertServerNotAlreadyRunning
import com.varabyte.kobweb.cli.common.findKobwebExecutionEnvironment
import com.varabyte.kobweb.cli.common.handleGradleOutput
import com.varabyte.kobweb.cli.common.isServerAlreadyRunningFor
import com.varabyte.kobweb.cli.common.kotter.chooseFromList
import com.varabyte.kobweb.cli.common.kotter.handleConsoleOutput
import com.varabyte.kobweb.cli.common.kotter.informGradleStarting
import com.varabyte.kobweb.cli.common.kotter.informInfo
import com.varabyte.kobweb.cli.common.kotter.newline
import com.varabyte.kobweb.cli.common.kotter.trySession
import com.varabyte.kobweb.cli.common.kotter.warnFallingBackToPlainText
import com.varabyte.kobweb.cli.common.relativeToCurrentDirectory
import com.varabyte.kobweb.cli.common.toMessageLinesString
import com.varabyte.kobweb.cli.common.tryWaitForCompletion
import com.varabyte.kobweb.server.api.ServerEnvironment
import com.varabyte.kobweb.server.api.SiteLayout
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.Session
import java.io.File

private enum class ExportState {
    EXPORTING,
    INTERRUPTED,
    FINISHING,
    FINISHED,
    CANCELLING,
    CANCELLED,
}

// Query the export layout if the user didn't pass it in explicitly using `--layout $layout`
private fun Session.queryUserForSiteLayout(): SiteLayout? {
    return chooseFromList(
        "Specify what kind of export layout you want to use.",
        SiteLayout.entries.toList(),
        choiceToString = { @Suppress("DEPRECATION") it.name.lowercase().capitalize() },
        produceInitialIndex = { SiteLayout.entries.indexOf(SiteLayout.STATIC) }
    ) { selectedLayout ->
        when (selectedLayout) {
            SiteLayout.FULLSTACK -> "Use for a project that provides both frontend (js) and backend (jvm) code."
            SiteLayout.STATIC -> "Use for a project that only provides only frontend (js) code (no backend) and whose output is compatible with static site hosting providers."
        }
    }?.also { chosenLayout ->
        newline()
        informInfo {
            val argsCopy = Globals.getValue(ProgramArgsKey).toMutableList()
            // Add layout args immediately after command (i.e. "export")
            argsCopy.addAll(1, listOf("--layout", chosenLayout.name.lowercase()))

            text("Running: ")
            cyan { text("kobweb ${argsCopy.joinToString(" ")}") }

            Globals[ProgramArgsKey] = argsCopy.toTypedArray()
        }
    }
}

fun handleExport(
    projectDir: File,
    siteLayout: SiteLayout?,
    useAnsi: Boolean,
    gradleArgsCommon: List<String>,
    gradleArgsExport: List<String>,
    gradleArgsStop: List<String>
) {
    // exporting is a production-only action
    findKobwebExecutionEnvironment(
        ServerEnvironment.PROD,
        projectDir.toPath(),
        useAnsi
    )?.use { kobwebExecutionEnvironment ->
        handleExport(
            siteLayout,
            useAnsi,
            kobwebExecutionEnvironment,
            gradleArgsCommon,
            gradleArgsExport,
            gradleArgsStop
        )
    }
}

private fun handleExport(
    siteLayout: SiteLayout?,
    useAnsi: Boolean,
    kobwebExecutionEnvironment: KobwebExecutionEnvironment,
    gradleArgsCommon: List<String>,
    gradleArgsExport: List<String>,
    gradleArgsStop: List<String>
) {
    val kobwebApplication = kobwebExecutionEnvironment.application
    val kobwebGradle = kobwebExecutionEnvironment.gradle

    var runInPlainMode = !useAnsi

    if (useAnsi && !trySession {
            if (isServerAlreadyRunningFor(kobwebApplication, kobwebGradle)) return@trySession

            val siteLayout = siteLayout ?: queryUserForSiteLayout() ?: return@trySession

            newline() // Put space between user prompt and eventual first line of Gradle output

            var exportState by liveVarOf(ExportState.EXPORTING)
            val gradleAlertBundle = GradleAlertBundle(this)

            val ellipsis = textAnimOf(Anims.ELLIPSIS)
            var exception by liveVarOf<Exception?>(null) // Set if ExportState.INTERRUPTED
            section {
                fun renderWarningsAndErrors(showNavigationHelp: Boolean = true) {
                    textLine()
                    gradleAlertBundle.renderWarningsAndErrors(this, showNavigationHelp)
                }

                // Add space between this block and Gradle text which will appear above
                textLine()
                when (exportState) {
                    ExportState.EXPORTING -> {
                        gradleAlertBundle.renderSyncMessage(this)
                        textLine("Exporting$ellipsis")
                        renderWarningsAndErrors()
                    }
                    ExportState.FINISHING -> {
                        textLine("Finishing up$ellipsis")
                        renderWarningsAndErrors()
                    }
                    ExportState.FINISHED -> {
                        textLine("Export finished successfully.")
                        renderWarningsAndErrors(showNavigationHelp = false)

                        text("You can run ")
                        cyan {
                            text(
                                buildString {
                                    append("kobweb run")
                                    kobwebApplication.path.relativeToCurrentDirectory()?.takeUnless { it.toString().isBlank() }?.let { relativePath ->
                                        append(" -p $relativePath")
                                    }
                                    append(" --layout ${siteLayout.name.lowercase()}")
                                    append(" --env prod")
                                }
                            )
                        }
                        textLine(" to preview your site.")
                    }
                    ExportState.CANCELLING -> {
                        yellow { textLine("Cancelling export$ellipsis") }
                        renderWarningsAndErrors()
                    }
                    ExportState.CANCELLED -> {
                        yellow { textLine("Export cancelled by user.") }
                        renderWarningsAndErrors(showNavigationHelp = false)
                    }
                    ExportState.INTERRUPTED -> {
                        gradleAlertBundle.renderWarningsAndErrors(this, showNavigationHelp = false)
                        red { textLine("Export interrupted by exception. Message(s):") }
                        textLine()
                        textLine(exception!!.toMessageLinesString())
                    }
                }
            }.run {
                kobwebGradle.onStarting = ::informGradleStarting

                fun interruptWithException(ex: Exception) {
                    exception = ex
                    exportState = ExportState.INTERRUPTED
                }
                val exportProcess = try {
                    kobwebGradle.export(siteLayout, gradleArgsCommon + gradleArgsExport).apply {
                        onAlert = { gradleAlertBundle.handleAlert(it) }
                    }
                } catch (ex: Exception) {
                    interruptWithException(ex)
                    return@run
                }
                exportProcess.lineHandler = { line, isError ->
                    handleGradleOutput(line, isError) { alert -> gradleAlertBundle.handleAlert(alert) }
                }

                onKeyPressed {
                    if (exportState == ExportState.EXPORTING && key == Keys.Q) {
                        exportProcess.cancel()
                        exportState = ExportState.CANCELLING
                    } else {
                        gradleAlertBundle.handleKey(key)
                    }
                }

                try {
                    exportProcess.waitForCompletion()
                    exportState = ExportState.FINISHING
                } catch (ex: Exception) {
                    if (exportState != ExportState.CANCELLING) {
                        exception = ex
                        // Uh oh something bad happened. Let's gracefully shut down the server and then show the error
                        // to the user.
                        exportState = ExportState.FINISHING
                    }
                }

                check(exportState in listOf(ExportState.FINISHING, ExportState.CANCELLING))

                val stopProcess = kobwebGradle.stopServer(gradleArgsCommon + gradleArgsStop)
                stopProcess.lineHandler = ::handleConsoleOutput
                stopProcess.tryWaitForCompletion()

                exportState = when {
                    exception != null -> ExportState.INTERRUPTED
                    exportState == ExportState.FINISHING -> ExportState.FINISHED
                    exportState == ExportState.CANCELLING -> ExportState.CANCELLING
                    else -> error("Unexpected state after export finished running ($exportState).")
                }
            }
        }) {
        warnFallingBackToPlainText()
        runInPlainMode = true
    }

    if (runInPlainMode) {
        kobwebApplication.assertServerNotAlreadyRunning()

        try {
            kobwebGradle
                // default to fullstack for legacy reasons
                .export(siteLayout ?: SiteLayout.FULLSTACK, gradleArgsCommon + gradleArgsExport)
                .waitForCompletion()
        } catch (ex: Exception) {
            throw CliktError("\nFailed to export a Kobweb site.\n\n${ex.toMessageLinesString()}")
        }

        kobwebGradle.stopServer(gradleArgsCommon + gradleArgsStop).waitForCompletion()
    }
}
