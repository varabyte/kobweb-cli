package com.varabyte.kobweb.cli.common.kotter

import com.varabyte.kobweb.cli.common.Anims
import com.varabyte.kobweb.cli.common.KobwebGradle
import com.varabyte.kobweb.cli.common.Validations
import com.varabyte.kotter.foundation.anim.text
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.input.Completions
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputChanged
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.input.runUntilInputEntered
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.bold
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.invert
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.RunScope
import com.varabyte.kotter.runtime.Session
import com.varabyte.kotter.runtime.render.RenderScope

const val SELECTION_CURSOR = '❯'

private enum class ProcessingState {
    IN_PROGRESS,
    FAILED,
    SUCCEEDED
}

fun RenderScope.cmd(name: String) {
    val parts = name.split(' ')
    parts.forEachIndexed { i, part ->
        if (i == 0) {
            cyan { text(part) }
        } else {
            text(part)
        }
        if (i < parts.lastIndex) {
            text(' ')
        }
    }
}

fun Session.processing(message: String, blockingWork: () -> Unit): Boolean {
    val spinner = textAnimOf(Anims.SPINNER)
    val ellipsis = textAnimOf(Anims.ELLIPSIS)
    var state by liveVarOf(ProcessingState.IN_PROGRESS)
    section {
        when (state) {
            ProcessingState.IN_PROGRESS -> yellow { text(spinner) }
            ProcessingState.FAILED -> red { text("✗") }
            ProcessingState.SUCCEEDED -> green { text("✓") }
        }

        text(' ')
        text(message)

        when (state) {
            ProcessingState.IN_PROGRESS -> text(ellipsis)
            ProcessingState.FAILED -> textLine("${Anims.ELLIPSIS.frames.last()} Failed.")
            ProcessingState.SUCCEEDED -> textLine("${Anims.ELLIPSIS.frames.last()} Done!")
        }
    }.run {
        state = try {
            blockingWork()
            ProcessingState.SUCCEEDED
        } catch (ex: Exception) {
            ex.printStackTrace()
            ProcessingState.FAILED
        }
    }

    return state == ProcessingState.SUCCEEDED
}

fun RenderScope.textErrorPrefix() {
    red { text("✗") }
    text(' ')
}

fun RenderScope.textError(message: String) {
    textErrorPrefix()
    textLine(message)
}

fun Session.informError(block: RenderScope.() -> Unit) {
    section {
        textErrorPrefix()
        block()
    }.run()
}

fun Session.informError(message: String) {
    informError { textLine(message) }
}

fun RenderScope.textInfoPrefix() {
    yellow { text('!') }
    text(' ')
}

// Note: Newlines in text will create multiple "!" lines
fun RenderScope.textInfo(message: String) {
    message.split("\n").forEach { line ->
        textInfoPrefix()
        textLine(line)
    }
}

fun Session.informInfo(block: RenderScope.() -> Unit) {
    section {
        textInfoPrefix()
        this.block()
    }.run()
}

// Note: Newlines in text will create multiple "!" lines
fun Session.informInfo(message: String) {
    section {
        textInfo(message)
    }.run()
}

fun Session.warn(message: String) {
    section {
        yellow { textLine(message) }
    }.run()
}

fun RenderScope.textQuestionPrefix() {
    cyan { text('?') }
    text(' ')
}

private fun RenderScope.promptQuestion(query: String, extra: RenderScope.() -> Unit = {}) {
    textQuestionPrefix()
    bold { textLine("$query ") }
    extra()
    text("> ")
}

fun Session.askYesNo(
    query: String,
    defaultAnswer: Boolean = true,
): Boolean {
    return askYesNo(query, null, defaultAnswer)
}

fun Session.askYesNo(
    query: String,
    note: String?,
    defaultAnswer: Boolean = true
): Boolean {
    var answer by liveVarOf(defaultAnswer)
    section {
        promptQuestion(query) {
            note?.let { textInfo(it) }
        }
        yesNo(answer, defaultAnswer)
        textLine()
    }.runUntilSignal {
        onYesNoChanged(valueOnCancel = null) {
            answer = isYes
            if (shouldAccept) signal()
        }
    }
    return answer
}

/**
 * @param validateAnswer Take a string (representing a user's answer), returning a new string which represents an error
 *   message, or null if no error.
 */
fun Session.queryUser(
    query: String,
    defaultAnswer: String?,
    validateAnswer: (String) -> String? = Validations::isNotEmpty
): String {
    return queryUser(query, null, defaultAnswer, validateAnswer)
}

fun Session.queryUser(
    query: String,
    note: String?,
    defaultAnswer: String?,
    validateAnswer: (String) -> String? = Validations::isNotEmpty
): String {
    var answer by liveVarOf("")
    var error by liveVarOf<String?>(null)
    section {
        promptQuestion(query) {
            note?.let { textInfo(it) }
        }
        if (answer.isNotEmpty()) {
            textLine(answer)
        } else {
            input(defaultAnswer?.let { Completions(it) })
            textLine()
            error?.let { error ->
                scopedState {
                    red()
                    invert()
                    textLine(error)
                }
            }
        }
        textLine()
    }.runUntilInputEntered {
        lateinit var possibleAnswer: String
        fun validateInput(input: String) {
            possibleAnswer = input.takeIf { it.isNotBlank() } ?: defaultAnswer.orEmpty()
            error = validateAnswer(possibleAnswer)
        }
        validateInput("")
        onInputChanged { validateInput(input) }
        onInputEntered {
            if (error == null) {
                answer = possibleAnswer
            } else {
                rejectInput()
            }
        }
    }
    return answer
}

/**
 * Choose a choice from a list of choices.
 *
 * This class supports arbitrary typed lists. Its return value is an item from the list, but nullable, as potentially
 * the choice was canceled. If [allowCancelling] is false, then the return value will always be non-null.
 *
 * @param addGapAfterPrompt If we should add space after the prompt and before the list of choices.
 */
fun <T> Session.chooseFromList(query: String, choices: List<T>, note: String? = null, addGapAfterPrompt: Boolean = true, choiceToString: (T) -> String = { it.toString() }, produceInitialIndex: () -> Int = { 0 }, allowCancelling: Boolean = true, extra: ((T) -> String)? = null): T? {
    check(choices.size >= 2) { "Must provide at least two choices" }

    var choiceIndex by liveVarOf(produceInitialIndex())
    var canceled by liveVarOf(false)

    section {
        textQuestionPrefix()
        bold {
            textLine(buildString {
                append(query)
                if (allowCancelling) {
                    append(" Choose one or press Escape to cancel.")
                }
            })
        }
        note?.let { textInfo(it) }

        if (addGapAfterPrompt) textLine()
        choices.forEachIndexed { index, choice ->
            text(if (index == choiceIndex) SELECTION_CURSOR else ' ')
            text(' ')
            cyan { textLine(choiceToString(choice)) }
        }
        if (extra != null) {
            textLine()
            yellow {
                textLine("  " + extra(choices[choiceIndex]))
            }
        }
        textLine()
    }.runUntilSignal {
        onKeyPressed {
            when (key) {
                Keys.Up -> choiceIndex =
                    (choiceIndex - 1).let { if (it < 0) choices.lastIndex else it }

                Keys.Down -> choiceIndex = (choiceIndex + 1) % choices.size
                Keys.Home -> choiceIndex = 0
                Keys.End -> choiceIndex = choices.lastIndex
                Keys.Enter -> signal()
                Keys.Escape, Keys.Q -> {
                    if (allowCancelling) {
                        canceled = true
                        signal()
                    }
                }
            }
        }
    }

    return choices[choiceIndex].takeUnless { canceled }
}

fun Session.queryUser(
    query: String,
    note: String?,
    choices: List<String>,
    defaultChoice: Int,
): String {
    return chooseFromList(
        query,
        choices,
        note,
        addGapAfterPrompt = false,
        choiceToString = { it },
        produceInitialIndex = { defaultChoice },
        allowCancelling = false
    )!! // Guaranteed non-null because cancelling not allowed
}

/**
 * Convenience method for adding a single line, useful to do before or after queries or information messages.
 */
fun Session.newline() {
    section { textLine() }.run()
}

fun RunScope.informGradleStarting(onStarting: KobwebGradle.OnStartingEvent) {
    aside {
        black(isBright = true) {
            textLine(onStarting.fullCommand)
        }
    }
}

fun RunScope.handleConsoleOutput(line: String, isError: Boolean) {
    aside {
        if (isError) red() else black(isBright = true)
        textLine(line)
    }
}

fun warnFallingBackToPlainText() {
    println("Kobweb could not initialize an ANSI terminal session. Falling back to plain text.")
    println("You can run Kobweb with `--notty` to avoid seeing this message.")
    println()
}

/**
 * Try running a session, returning false if it could not start.
 *
 * The main reason a session would not start is if the terminal environment is not interactive, which is common in
 * environments like docker containers and CIs. In that case, the code that calls this method can handle the boolean
 * signal and run some fallback code that doesn't require interactivity.
 */
fun trySession(block: Session.() -> Unit): Boolean {
    var sessionStarted = false
    try {
        session {
            sessionStarted = true
            block()
        }
    } catch (ex: Exception) {
        if (!sessionStarted) {
            return false
        } else {
            // This exception came from after startup, when the user was
            // interacting with Kotter. Crashing with an informative stack
            // is probably the best thing we can do at this point.
            throw ex
        }
    }

    return true
}
