package com.varabyte.kobweb.cli.common.version

import com.varabyte.kobweb.cli.common.template.KobwebTemplate
import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.white
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.Session
import com.varabyte.kotterx.decorations.BorderCharacters
import com.varabyte.kotterx.decorations.bordered

private val kobwebCliVersionString get() = System.getProperty("kobweb.version", "0.0.0-SNAPSHOT")

val kobwebCliVersion: SemVer.Parsed by lazy {
    SemVer.parse(kobwebCliVersionString) as SemVer.Parsed
}

/**
 * Returns true if the given template is supported by the current version of the Kobweb CLI.
 *
 * This assumes that the "minimumVersion" value in the template metadata was properly set. If it can't be parsed,
 * we silently hide it instead of crashing.
 */
@Suppress("RedundantIf") // Code is more readable when symmetric
val KobwebTemplate.versionIsSupported: Boolean
    get() {
        val minVersion = metadata.minimumVersion?.let { SemVer.parse(it) as? SemVer.Parsed }
        if (minVersion != null && minVersion > kobwebCliVersion) {
            return false
        }

        val maxVersion = metadata.maximumVersion?.let { SemVer.parse(it) as? SemVer.Parsed }
        if (maxVersion != null && maxVersion < kobwebCliVersion) {
            return false
        }

        return true
    }

fun Session.reportUpdateAvailable(oldVersion: SemVer.Parsed, newVersion: SemVer.Parsed) {
    section {
        textLine()
        yellow {
            bordered(borderCharacters = BorderCharacters.CURVED, paddingLeftRight = 2, paddingTopBottom = 1) {
                white()
                text("Update available: ")
                black(isBright = true) {
                    text(oldVersion.toString())
                }
                text(" → ")
                green {
                    text(newVersion.toString())
                }
                textLine(); textLine()
                text("Please review ")
                cyan(isBright = false) { text("https://github.com/varabyte/kobweb#update-the-kobweb-binary") }
                textLine()
                textLine("for instructions.")
            }
        }
    }.run()
}
