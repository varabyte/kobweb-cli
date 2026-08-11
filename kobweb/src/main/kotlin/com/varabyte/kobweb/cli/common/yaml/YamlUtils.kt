package com.varabyte.kobweb.cli.common.yaml

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap

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

fun YamlNode.collectValues(): Map<String, String> {
    val scalars = mutableMapOf<String, String>()
    yamlMap.collectValuesInto(scalars, emptyList())
    return scalars
}
