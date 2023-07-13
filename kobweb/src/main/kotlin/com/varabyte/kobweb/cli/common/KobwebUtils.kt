package com.varabyte.kobweb.cli.common

import com.varabyte.kobweb.common.error.KobwebException
import com.varabyte.kobweb.project.KobwebApplication
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.Session
import java.nio.file.Path

fun assertKobwebApplication(path: Path): KobwebApplication {
    return try {
        KobwebApplication(path)
    } catch (ex: KobwebException) {
        throw KobwebException("This command must be called in a Kobweb application module.")
    }
}

fun KobwebApplication.assertServerNotAlreadyRunning() {
    ServerStateFile(this.kobwebFolder).content?.let { serverState ->
        if (serverState.isRunning()) {
            throw KobwebException("Cannot execute this command as a server is already running (PID=${serverState.pid}). Consider running `kobweb stop` if this is unexpected.")
        }
    }
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
    return try {
        assertKobwebApplication(path)
    } catch (ex: KobwebException) {
        informError(ex.message!!)
        null
    }
}

fun Session.isServerAlreadyRunningFor(project: KobwebApplication): Boolean {
    return try {
        project.assertServerNotAlreadyRunning()
        false
    } catch (ex: KobwebException) {
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
