package com.varabyte.kobweb.cli.create.freemarker.methods

import org.jetbrains.annotations.ApiStatus

/**
 * Utility method for escaping relevant control characters before inserting text into a yaml string.
 */
@ApiStatus.AvailableSince("0.9.17")
class EscapeYamlStringMethod : SingleArgMethodModel() {
    override fun exec(value: String): String {
        return value
            // Replace backslash first, else we'd modify any quote substitutions (e.g. '"' -> '\"' -> '\\"')
            .replace("\\", """\\""")
            .replace("\"", """\"""")
    }
}