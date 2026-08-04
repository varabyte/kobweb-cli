package com.varabyte.kobweb.cli.common.settings

import com.charleskorn.kaml.Yaml
import com.varabyte.kobweb.common.yaml.nonStrictDefault
import com.varabyte.kobweb.project.io.LiveFile
import dev.dirs.ProjectDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
class Settings(
    val upgradeCheck: UpgradeCheck = UpgradeCheck()
) {
    @Serializable
    data class UpgradeCheck(
        var lastSuccessfulCheck: Instant = Instant.fromEpochMilliseconds(0),
        val notifyFrequency: Duration = 1.days,
    ) {
        /**
         * Check if enough time has passed that we should show an "upgrade available" notification to the user.
         *
         * If this method returns true then [lastSuccessfulCheck] will be updated as a side effect.
         */
        fun shouldNotifyUser(): Boolean {
            val now = Clock.System.now()
            return (now - lastSuccessfulCheck > notifyFrequency)
                .also { shouldNotify -> if (shouldNotify) lastSuccessfulCheck = now }
        }
    }

}

object SettingsFile {
    private val EMPTY_BYTE_ARRAY = ByteArray(0)

    private val configFile = run {
        val projectDirs = ProjectDirectories.from("com", "Varabyte", "Kobweb CLI")
        LiveFile(Path(projectDirs.configDir, "settings.yaml"))
    }

    /**
     * Read out a new instance of a [Settings] object, as loading from disk.
     *
     * This will be an empty settings object if the file doesn't exist yet.
     *
     * You are encouraged to make modifications to your settings object, but you will need to call [writeSettings] to
     * save those changes to disk.
     *
     * Note that it is not safe to have two different threads read settings and then write settings at the same time.
     */
    fun readSettings(): Settings {
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
     * Although we are pretty sure this will always succeed, this method protects against exceptions, as we'd rather a
     * user with a system that can't save settings for some reason (strict permissions? Unknown OS?) simply fail to
     * write to settings rather than throw an exception.
     *
     * See also: [readSettings].
     */
    fun writeSettings(settings: Settings): Boolean {
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
