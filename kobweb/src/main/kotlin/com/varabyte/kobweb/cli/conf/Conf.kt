package com.varabyte.kobweb.cli.conf

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap
import com.varabyte.kobweb.cli.common.assertKobwebApplication
import com.varabyte.kobweb.cli.common.assertKobwebConfIn
import com.varabyte.kobweb.project.conf.KobwebConfFile
import java.io.File
import kotlin.io.path.readText

private fun YamlMap.collectValuesInto(map: MutableMap<String, String>, pathPrefix: List<String>) {
    this.entries.forEach { (key, value) ->
        if (value is YamlScalar) {
            val keyPrefix = if (pathPrefix.isNotEmpty()) pathPrefix.joinToString(".") + "." else ""
            map["$keyPrefix${key.content}"] = value.content
        } else if (value is YamlMap) {
            value.collectValuesInto(map, pathPrefix + key.content)
        }
    }
}

private fun YamlNode.collectValues(): Map<String, String> {
    val scalars = mutableMapOf<String, String>()
    yamlMap.collectValuesInto(scalars, emptyList())
    return scalars
}

fun handleConf(query: String, projectDir: File) {
    val kobwebApplication = assertKobwebApplication(projectDir.toPath())
    if (query.isBlank()) return // No query? OK I guess we're done

    // Even though we don't use its return value, we use this as a side effect to show a useful error message to users
    // if the conf.yaml file is not found.
    assertKobwebConfIn(kobwebApplication.kobwebFolder)

    // Instead of using Kobweb's KobwebConf deserialized class, we use a generically parsed Yaml result. This ensures
    // that this command will work flexibly with any version of any conf.yaml file, even if a new field has since been
    // added.
    val yamlNode = Yaml.default.parseToYamlNode(KobwebConfFile(kobwebApplication.kobwebFolder).path.readText())

    val yamlValues = yamlNode.collectValues()
    val answer = yamlValues[query]

    if (answer != null) {
        println(answer)
    } else {
        System.err.println(buildString {
            appendLine("Invalid query.")
            appendLine()
            appendLine("Possible queries are:")

            yamlValues.keys.sorted().forEach { key ->
                append(" • ")
                appendLine(key)
            }
        })
    }
}
