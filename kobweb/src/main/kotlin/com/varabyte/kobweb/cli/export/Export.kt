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
import com.varabyte.kobweb.cli.common.showStaticSiteLayoutWarning
import com.varabyte.kobweb.cli.common.waitForAndCheckForException
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
    FINISHING,
    FINISHED,
    CANCELLING,
    CANCELLED,
    INTERRUPTED,
}

// Query the export layout if the user didn't pass it in explicitly using `--layout $layout`
private fun Session.queryUserForSiteLayout(): SiteLayout? {
    return chooseFromList(
        "Specify what kind of export layout you want to use.",
        SiteLayout.entries.toList(),
        itemToString = { @Suppress("DEPRECATION") it.name.lowercase().capitalize() },
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

            if (siteLayout.isStatic) {
                showStaticSiteLayoutWarning()
            }

            var exportState by liveVarOf(ExportState.EXPORTING)
            val gradleAlertBundle = GradleAlertBundle(this)

            var cancelReason by liveVarOf("")
            val ellipsis = textAnimOf(Anims.ELLIPSIS)
            var exception by liveVarOf<Exception?>(null) // Set if ExportState.INTERRUPTED
            section {
                textLine() // Add space between this block and Gradle text which will appear above
                gradleAlertBundle.renderInto(this)
                when (exportState) {
                    ExportState.EXPORTING -> textLine("Exporting$ellipsis")
                    ExportState.FINISHING -> textLine("Finishing up$ellipsis")
                    ExportState.FINISHED -> {
                        textLine("Export finished successfully.")
                        textLine()

                        text("You can run ")
                        cyan {
                            text(
                                buildString {
                                    append("kobweb run")
                                    kobwebApplication.path.relativeToCurrentDirectory()?.let { relativePath ->
                                        append(" -p ")
                                        append(relativePath)
                                    }
                                    append(" --env prod")
                                    if (siteLayout.isStatic) append(" --layout static")
                                }
                            )
                        }
                        textLine(" to preview your site.")
                    }
                    ExportState.CANCELLING -> yellow { textLine("Cancelling export: $cancelReason$ellipsis") }
                    ExportState.CANCELLED -> yellow { textLine("Export cancelled: $cancelReason") }
                    ExportState.INTERRUPTED -> {
                        red { textLine("Interrupted by exception:") }
                        textLine()
                        textLine(exception!!.stackTraceToString())
                    }
                }
            }.run {
                kobwebGradle.onStarting = ::informGradleStarting

                val exportProcess = try {
                    kobwebGradle.export(siteLayout, gradleArgsCommon + gradleArgsExport)
                } catch (ex: Exception) {
                    exception = ex
                    exportState = ExportState.INTERRUPTED
                    return@run
                }
                exportProcess.lineHandler = { line, isError ->
                    handleGradleOutput(line, isError) { alert -> gradleAlertBundle.handleAlert(alert) }
                }

                onKeyPressed {
                    if (exportState == ExportState.EXPORTING && (key == Keys.Q || key == Keys.Q_UPPER)) {
                        cancelReason = "User requested cancellation"
                        exportProcess.cancel()
                        exportState = ExportState.CANCELLING
                    } else {
                        gradleAlertBundle.handleKey(key)
                    }
                }

                if (exportProcess.waitForAndCheckForException() != null) {
                    if (exportState != ExportState.CANCELLING) {
                        cancelReason =
                            "Server failed to build. Please check Gradle output and fix the errors before retrying."
                        exportState = ExportState.CANCELLING
                    }
                }
                if (exportState == ExportState.EXPORTING) {
                    exportState = ExportState.FINISHING
                }
                check(exportState in listOf(ExportState.FINISHING, ExportState.CANCELLING))

                val stopProcess = kobwebGradle.stopServer(gradleArgsCommon + gradleArgsStop)
                stopProcess.lineHandler = ::handleConsoleOutput
                stopProcess.waitFor()

                exportState = if (exportState == ExportState.FINISHING) ExportState.FINISHED else ExportState.CANCELLED
            }
        }) {
        warnFallingBackToPlainText()
        runInPlainMode = true
    }

    if (runInPlainMode) {
        kobwebApplication.assertServerNotAlreadyRunning()

        val exportFailed = kobwebGradle
            // default to fullstack for legacy reasons
            .export(siteLayout ?: SiteLayout.FULLSTACK, gradleArgsCommon + gradleArgsExport)
            .waitForAndCheckForException() != null

        kobwebGradle.stopServer(gradleArgsCommon + gradleArgsStop).waitFor()

        if (exportFailed) throw CliktError("Export failed. Please check Gradle output and resolve any errors before retrying.")
    }
}
