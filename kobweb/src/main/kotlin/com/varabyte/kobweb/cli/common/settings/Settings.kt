package com.varabyte.kobweb.cli.common.settings

import com.charleskorn.kaml.Yaml
import com.varabyte.kobweb.common.yaml.nonStrictDefault
import com.varabyte.kobweb.project.io.LiveFile
import dev.dirs.ProjectDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.annotations.ApiStatus
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Data values exposed to users that are used to define values globally useful to a Kobweb project.
 */
@Serializable
data class Settings(
    val upgradeCheck: UpgradeCheck = UpgradeCheck()
) {
    /**
     * Settings relevant to an occasional "Is there a new version available?" check.
     */
    @Serializable
    @ApiStatus.AvailableSince("0.9.22")
    data class UpgradeCheck(
        /** A recording of the last system clock time that we checked for an upgrade. */
        var lastChecked: Instant = Instant.fromEpochMilliseconds(0),
        /** How often to check. An interval of 1 day means we only ever check at most once a day. */
        val interval: Duration = 1.days,
    ) {
        /**
         * Check if enough time has passed that we should show an "upgrade available" notification to the user.
         *
         * If this method returns `true` then [lastChecked] will be updated as a side effect.
         */
        fun shouldNotifyUser(): Boolean {
            val now = Clock.System.now()
            return (now - lastChecked > interval)
                .also { shouldNotify -> if (shouldNotify) lastChecked = now }
        }
    }

}

private fun Settings.deepCopy(): Settings {
    return Yaml.nonStrictDefault.decodeFromString(Yaml.nonStrictDefault.encodeToString(this))
}

object SettingsFile {
    private val lock = Any()

    private val EMPTY_BYTE_ARRAY = ByteArray(0)

    private val configFile = run {
        val projectDirs = ProjectDirectories.from("com", "Varabyte", "Kobweb CLI")
        LiveFile(Path(projectDirs.configDir, "settings.yaml"))
    }

    /**
     * Read out a new instance of a [Settings] object from disk.
     *
     * If modified, changes will automatically be saved back to disk.
     *
     * This method is synchronized, so only one thread can access settings at a time. Therefore, the [block] callback
     * ideally should not live too long.
     */
    fun <R> useSettings(block: Settings.() -> R): R {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        val result: R
        synchronized(lock) {
            val settings = readSettings()
            val settingsCopy = settings.deepCopy()
            result = settings.block()

            if (settings != settingsCopy) {
                writeSettings(settings)
            }
        }
        return result
    }

    /**
     * Read out a new, mutable instance of a [Settings] object, loaded from disk.
     *
     * This will be an empty settings object if the file doesn't exist yet.
     *
     * Changes made to the settings object must be written back to disk via [writeSettings] or they will be lost.
     *
     * Note that it is not safe to have two different threads read settings and then write settings at the same time.
     */
    private fun readSettings(): Settings {
        return (configFile.content ?: EMPTY_BYTE_ARRAY).let { bytes ->
            try {
                Yaml.nonStrictDefault.decodeFromString<Settings>(bytes.decodeToString())
            } catch (_: Exception) {
                Settings()
            }
        }
    }

    /**
     * Commit a new instance of [Settings] to disk.
     *
     * Although we are pretty sure this will always succeed, this method protects against exceptions. We'd rather a user
     * with a system that can't save settings for some reason (strict permissions? Unknown OS?) fail to write to
     * settings rather than throw an exception.
     *
     * See also: [readSettings].
     */
    private fun writeSettings(settings: Settings): Boolean {
        return try {
            val filePath = configFile.path
            if (!filePath.parent.exists()) {
                filePath.parent.createDirectories()
            }
            val serialized = Yaml.nonStrictDefault.encodeToString(settings)
            filePath.writeText(serialized)
            true
        } catch (_: Exception) { false }
    }
}
