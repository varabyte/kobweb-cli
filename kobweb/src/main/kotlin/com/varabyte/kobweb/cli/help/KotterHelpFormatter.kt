package com.varabyte.kobweb.cli.help

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.AbstractHelpFormatter
import com.github.ajalt.clikt.output.HelpFormatter
import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.blue
import com.varabyte.kotter.foundation.text.bold
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.white
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.util.buildAnsiString

private fun pad(amount: Int) = " ".repeat(amount)

private const val MAX_CONTENT_WIDTH = 80

private const val START_PADDING = 2

// If an option name + meta is too long, add a newline before showing its description, e.g.
//
// Options:
//
//  --foo        This is a description of foo
//  -l, --layout=(VERTICAL|HORIZONTAL|GRID)
//               This is a description of layout <- starts on newline
private const val MAX_OPTION_DESC_INDENT = 26

private const val TERM_DESC_MARGIN = 2

// Kotter's buildAnsiString puts a trailing newline at the end of the output; we don't want that here
private fun inlineAnsiString(block: RenderScope.() -> Unit): String {
    return buildAnsiString(block).removeSuffix("\n")
}

private fun CharSequence.stripAnsiEscapeCodes(): String {
    return this.replace(Regex("\u001B\\[[;\\d]*m"), "")
}

private val CharSequence.lengthWithoutAnsi: Int get() = this.stripAnsiEscapeCodes().length

@Suppress("RedundantOverride")
class KotterHelpFormatter(
    /**
     * The current command's context.
     */
    context: Context,
    /**
     * The string to show before the names of required options, or null to not show a mark.
     */
    requiredOptionMarker: String? = null,
    /**
     * If true, the default values will be shown in the help text for parameters that have them.
     */
    showDefaultValues: Boolean = false,
    /**
     * If true, a tag indicating the parameter is required will be shown after the description of
     * required parameters.
     */
    showRequiredTag: Boolean = false,
) : AbstractHelpFormatter<String>(
    context,
    requiredOptionMarker,
    showDefaultValues,
    showRequiredTag
) {
    override fun formatHelp(
        error: UsageError?,
        prolog: String,
        epilog: String,
        parameters: List<HelpFormatter.ParameterHelp>,
        programName: String,
    ): String {
        val parts = collectHelpParts(error, prolog, epilog, parameters, programName)
        return parts.joinToString("\n\n") { it.removeSuffix("\n") }
    }

    private fun wrap(text: String, pad: Int = 0, width: Int = MAX_CONTENT_WIDTH - pad): String {
        return text.split('\n').joinToString("\n") { line ->
            if (line.lengthWithoutAnsi > width) {
                buildString {
                    val remaining = StringBuilder(line)
                    val nextWord = StringBuilder()
                    var lineLength = 0

                    fun applyInitialPadding() {
                        if (lineLength == 0 && pad > 0) {
                            append(" ".repeat(pad))
                            lineLength += pad
                        }
                    }

                    fun flushWord() {
                        if (lineLength + nextWord.lengthWithoutAnsi > width) {
                            if (lineLength > 0) {
                                append('\n')
                                lineLength = 0
                                while (nextWord.first().isWhitespace()) nextWord.deleteAt(0)
                            }
                        }

                        while (nextWord.lengthWithoutAnsi > width) {
                            applyInitialPadding()
                            appendLine(nextWord.substring(0, width))
                            nextWord.deleteRange(0, width)
                        }

                        applyInitialPadding()
                        append(nextWord)
                        lineLength += nextWord.lengthWithoutAnsi
                        nextWord.clear()
                    }

                    while (remaining.isNotEmpty()) {
                        val nextChar = remaining.first(); remaining.deleteAt(0)
                        if (nextChar.isWhitespace() && nextWord.isNotEmpty()) {
                            flushWord()
                        }
                        nextWord.append(nextChar)
                    }
                    flushWord()
                }
            }
            else {
                line
            }
        }
    }

    override fun styleRequiredMarker(name: String): String {
        return super.styleRequiredMarker(name)
    }

    // Used for misc tooltips
    //
    // --env=(DEV|PROD)  The environment to run in. >>(default: DEV)<<
    override fun styleHelpTag(name: String): String {
        return super.styleHelpTag(name)
    }

    // Used for option names
    //
    // Options:
    //
    //  >>--env<<=(DEV|PROD)
    //  >>-h<<
    override fun styleOptionName(name: String): String {
        return inlineAnsiString {
            bold { blue { text(name) } }
        }
    }

    // Used for argument names
    //
    // Arguments:
    //   >><filename><<   The file to perform an operation on
    override fun styleArgumentName(name: String): String {
        return styleOptionName(name)
    }

    // Used for subcommand names
    //
    // Commands:
    //   >>version<<  Print the version of this binary
    //   >>run<<      Run a server
    override fun styleSubcommandName(name: String): String {
        return styleOptionName(name)
    }

    // Used for section titles
    //
    // >>Options:<<
    //   ...
    //
    // >>Commands:<<
    //   ...
    override fun styleSectionTitle(title: String): String {
        return inlineAnsiString {
            yellow(isBright = true) { bold { text(title) } }
        }
    }

    // >>Usage:<< ...
    override fun styleUsageTitle(title: String): String {
        return styleSectionTitle(title)
    }

    // >>Error:<< ...
    override fun styleError(title: String): String {
        return inlineAnsiString {
            red { text(title) }
        }
    }

    // Example args in option list
    //
    // Options:
    //
    //   --env=>>(DEV|PROD)<<
    //   --path=>><path><<
    override fun styleMetavar(metavar: String): String {
        return inlineAnsiString { yellow { text(metavar.lowercase()) } }
    }


    // Usage: command >>[<options>] <command> [<args>]<<
    override fun styleOptionalUsageParameter(parameter: String): String {
        return inlineAnsiString {
            white(isBright = false) { text(parameter) }
        }
    }

    override fun styleRequiredUsageParameter(parameter: String): String {
        return super.styleRequiredUsageParameter(parameter)
    }

    // Error: no such subcommand foo
    override fun renderError(
        parameters: List<HelpFormatter.ParameterHelp>,
        error: UsageError,
    ) = renderErrorString(parameters, error)

    // Usage: foo [<options>] <command> [<args>]
    override fun renderUsage(
        parameters: List<HelpFormatter.ParameterHelp>,
        programName: String,
    ) = buildString {
        val params = renderUsageParametersString(parameters)
        val title = localization.usageTitle()

        append(styleUsageTitle(title))
        append(' ')
        append(programName)
        if (params.isNotEmpty()) {
            append(' ')
            append(params)
        }
    }

    // Usage: command
    //
    //   >> Do a command <<
    //
    // Options:
    //   ...
    override fun renderProlog(prolog: String): String {
        return wrap(prolog, pad = 2)
    }

    // ???
    override fun renderEpilog(epilog: String): String {
        return wrap(epilog)
    }

    // Options:
    //
    //   -h, --help  Show this message and exit
    //   --env       The environment to run in
    //
    // Commands:
    //
    //    run        Run a server
    //    stop       Stop the server
    //
    // Above, "Options:" and "Commands:" are titles and the rest is the content. You can have multiple sections, one
    // after the other, and they should be separated by a newline.
    override fun renderParameters(parameters: List<HelpFormatter.ParameterHelp>): String {
        return buildString {
            collectParameterSections(parameters).forEach { (title, content) ->
                appendLine(title)
                appendLine()
                appendLine(content)
                appendLine()
            }
        }
    }

    // A list of options being rendered
    //
    // Options:
    //
    //   --foo
    //   --bar
    //   --baz
    //
    // `parameters` is a list of those options. help may exist if rendering an option group
    override fun renderOptionGroup(
        help: String?,
        parameters: List<HelpFormatter.ParameterHelp.Option>,
    ): String = buildString {
        if (help != null) {
            appendLine()
            appendLine(wrap(help, pad = 2))
            appendLine()
        }
        val options = parameters.map { renderOptionDefinition(it) }
        append(buildParameterList(options))
    }

    // --env=(DEV|PROD)    The environment to run in
    // ^^^^(term)^^^^^^    ^^^^^^(definition)^^^^^^^
    //
    // Marker will be set for required options (which Kobweb does not have at the moment)
    override fun renderDefinitionTerm(row: DefinitionRow): String {
        val rowMarker = row.marker
        val termPrefix = when {
            rowMarker.isNullOrEmpty() -> pad(START_PADDING)
            else -> rowMarker + pad(START_PADDING).drop(rowMarker.length).ifEmpty { " " }
        }
        return termPrefix + row.term
    }

    override fun renderDefinitionDescription(row: DefinitionRow): String {
        val optionRegex = Regex("--\\w+") // e.g. "--option"
        val inlineCodeRegex = Regex("`(\\w+)`") // e.g. "`command`"
        val defaultValueRegex = Regex("\\(default: (.+)\\)")

        var result = row.description.replace(" (default: none)", "")
        result = optionRegex.replace(result) { matchResult ->
            styleOptionName(matchResult.value)
        }
        result = inlineCodeRegex.replace(result) { matchResult ->
            styleMetavar(matchResult.groupValues[1])
        }
        result = defaultValueRegex.replace(result) { matchResult ->
            "(default: ${styleMetavar(matchResult.groupValues[1])})"
        }
        return result
    }

    // Handle rendering out the list of terms to descriptions.
    // We do some calculations so the options feel like they exist in a table, for example:
    //
    // ```
    // Options:
    //
    //   --env=(DEV|PROD)  Whether the server should run in development mode or
    //                     production. (default: DEV)
    //   -t, --tty         Enable TTY support (default). Tries to run using ANSI
    //                     support in an interactive mode if it can. Falls back
    //                     to `--notty` otherwise.
    //   --notty           Explicitly disable TTY support. In this case, runs in
    //                     plain mode, logging output sequentially without
    //                     listening for user input, which is useful for CI
    //                     environments or Docker containers.
    //   ...
    // ```
    //
    // If the initial term is too long, we start the description on a new line.
    //
    // ```
    //   ...
    //   -f, --foreground   Keep kobweb running in the foreground. This value is
    //                      ignored unless in --notty mode.
    //   -l, --layout=(FULLSTACK|STATIC|KOBWEB)
    //                      Specify the organizational layout of the site files.
    //                      NOTE: The option `kobweb` is deprecated and will be
    //                      removed in a future version. Please use `fullstack`
    //                      instead. (default: FULLSTACK)
    //   -p, --path=<path>  The path to the Kobweb application module. (default:
    //                      the current directory)
    //   ...
    // ```
    override fun buildParameterList(rows: List<DefinitionRow>): String {
        val rawTerms = rows.map { it.term.stripAnsiEscapeCodes() }
        val maxTermLength = (rawTerms.filter { it.length < MAX_OPTION_DESC_INDENT }.maxOfOrNull { it.length } ?: MAX_OPTION_DESC_INDENT)
        val indent = " ".repeat(maxTermLength + START_PADDING + 2)
        return rows.joinToString("\n") { row ->
            val term = renderDefinitionTerm(row)
            val rawTerm = row.term.stripAnsiEscapeCodes()
            val definition = wrap(renderDefinitionDescription(row), width = MAX_CONTENT_WIDTH - maxTermLength - START_PADDING - TERM_DESC_MARGIN)
                .replace("\n", "\n$indent")

            if (rawTerm.length < MAX_OPTION_DESC_INDENT) {
                term + pad(maxTermLength - rawTerm.length + TERM_DESC_MARGIN) + definition
            } else {
                term + "\n" + pad(START_PADDING + maxTermLength + TERM_DESC_MARGIN) + definition
            }
        }
    }
}
