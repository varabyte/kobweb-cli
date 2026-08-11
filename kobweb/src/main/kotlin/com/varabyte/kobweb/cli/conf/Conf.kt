package com.varabyte.kobweb.cli.conf

import com.charleskorn.kaml.Yaml
import com.varabyte.kobweb.cli.common.assertKobwebApplication
import com.varabyte.kobweb.cli.common.assertKobwebConfIn
import com.varabyte.kobweb.cli.common.yaml.collectValues
import com.varabyte.kobweb.project.conf.KobwebConfFile
import java.io.File
import kotlin.io.path.readText

fun handleConf(query: String?, projectDir: File) {
    val kobwebApplication = assertKobwebApplication(projectDir.toPath())

    // Even though we don't use its return value, we use this as a side effect to show a useful error message to users
    // if the conf.yaml file is not found.
    assertKobwebConfIn(kobwebApplication.kobwebFolder)

    // Instead of using Kobweb's KobwebConf deserialized class, we use a generically parsed Yaml result. This ensures
    // that this command will work flexibly with any version of any conf.yaml file, even if a new field has since been
    // added.
    val yamlNode = Yaml.default.parseToYamlNode(KobwebConfFile(kobwebApplication.kobwebFolder).path.readText())

    val yamlValues = yamlNode.collectValues()

    fun StringBuilder.appendPossibleQueries() {
        appendLine("Possible queries are:")
        yamlValues.keys.sorted().forEach { key ->
            append(" • ")
            appendLine(key)
        }
    }

    if (query.isNullOrBlank()) {
        println(buildString { appendPossibleQueries() })
    } else {
        val answer = yamlValues[query]

        if (answer != null) {
            println(answer)
        } else {
            System.err.println(buildString {
                appendLine("Invalid query.")
                appendLine()
                appendPossibleQueries()
            })
        }
    }
}
