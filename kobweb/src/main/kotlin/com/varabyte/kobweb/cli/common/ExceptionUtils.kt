package com.varabyte.kobweb.cli.common

fun Throwable.toMessageLines(): List<String> = generateSequence(this) { it.cause }
    .mapNotNull { it.message }
    .flatMap { it.lines() }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

fun Throwable.toMessageLinesString(): String = toMessageLines().let { lines ->
    if (lines.size == 1) return lines.first() else {
        lines.joinToString("\n") { "• $it" }
    }
}
