package com.varabyte.kobweb.cli.common.version

import kotlin.math.max

/**
 * A simple SemVar parser.
 *
 * See also: https://semver.org/
 *
 * Use [SemVer.parse] to create an instance.
 */
sealed interface SemVer {
    class Parsed(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : SemVer, Comparable<Parsed> {
        init {
            require(major >= 0) { "Major version must be >= 0" }
            require(minor >= 0) { "Minor version must be >= 0" }
            require(patch >= 0) { "Patch version must be >= 0" }
        }

        private fun comparePreReleaseIdentifiers(first: String?, second: String?): Int {
            return when {
                // If present vs. not present, the one with takes less precedence
                // e.g. 1.0.0-alpha < 1.0.0
                first != null && second == null -> -1
                first == null && second != null -> 1

                first != null && second != null -> {
                    val firstParts = first.split('.')
                    val secondParts = second.split('.')

                    var compareResult = 0
                    for (i in 0 .. max(firstParts.lastIndex, secondParts.lastIndex)) {
                        // If here, one identifier has more parts than the other, but otherwise they are equal
                        // For example: "1.0.0-alpha" vs "1.0.0-alpha.1"
                        // In that case, the one with more parts is considered larger
                        if (i > firstParts.lastIndex) {
                            compareResult = -1
                        } else if (i > secondParts.lastIndex) {
                            compareResult = 1
                        }

                        if (compareResult != 0) break

                        // Check individual parts for comparison, e.g. the "alpha" and "beta" in
                        // "1.0.0-alpha.2" vs "1.0.0-beta.11"
                        val firstPart = firstParts[i]
                        val secondPart = secondParts[i]
                        if (firstPart == secondPart) {
                            continue
                        }

                        val firstPartAsInt = firstPart.toIntOrNull()
                        val secondPartAsInt = secondPart.toIntOrNull()

                        compareResult = if (firstPartAsInt != null && secondPartAsInt != null) {
                            firstPartAsInt.compareTo(secondPartAsInt)
                        } else {
                            firstPart.compareTo(secondPart)
                        }
                        break
                    }
                    compareResult
                }
                else -> 0
            }
        }

        override fun compareTo(other: Parsed): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)

            return comparePreReleaseIdentifiers(preRelease, other.preRelease)
        }

        override fun toString(): String = "$major.$minor.$patch" + preRelease?.let { "-$it" }.orEmpty()
    }

    class Unparsed(val text: String) : SemVer {
        override fun toString() = text
    }

    companion object {
        /**
         * Attempt to parse a simple SemVer string.
         *
         * Returns [Parsed] if the string is a valid SemVer, otherwise [Unparsed].
         *
         * Note that this is a very simple parser, and doesn't support pre-release suffixes.
         */
        fun parse(text: String): SemVer {
            val (versionPart, preReleasePart) = text.split('-', limit = 2).let { parts ->
                // There may not be a pre-release suffix...
                parts[0] to parts.getOrNull(1)
            }

            val versionParts = versionPart.split('.')
            if (versionParts.size != 3) {
                return Unparsed(text)
            }
            return try {
                Parsed(
                    major = versionParts[0].toIntOrNull() ?: return Unparsed(text),
                    minor = versionParts[1].toIntOrNull() ?: return Unparsed(text),
                    patch = versionParts[2].toIntOrNull() ?: return Unparsed(text),
                    preRelease = preReleasePart,
                )
            } catch (ex: IllegalArgumentException) {
                Unparsed(text)
            }
        }
    }
}

val SemVer.Parsed.isSnapshot: Boolean
    get() = preRelease == "SNAPSHOT"

fun SemVer.Parsed.withoutPreRelease() = if (this.preRelease == null) this else SemVer.Parsed(major, minor, patch)