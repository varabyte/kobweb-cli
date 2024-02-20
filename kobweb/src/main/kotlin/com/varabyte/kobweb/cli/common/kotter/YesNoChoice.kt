package com.varabyte.kobweb.cli.common.kotter

import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.text.bold
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.white
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import com.varabyte.kotter.runtime.Section
import com.varabyte.kotter.runtime.concurrent.createKey
import com.varabyte.kotter.runtime.render.RenderScope

private val YesNoStateKey = Section.Lifecycle.createKey<Boolean>()

private fun RenderScope.wrapIf(condition: Boolean, before: Char, after: Char, block: RenderScope.() -> Unit) {
    text(if (condition) before else ' ')
    scopedState {
        block()
    }
    text(if (condition) after else ' ')
}

private fun RenderScope.choiceColor(isDefault: Boolean) {
    white(isBright = isDefault)
}

// NOTE: In this current (lazy) implementation, only one yes-no block is allowed per section.
// We can revisit this later if we need something more complex.
fun MainRenderScope.yesNo(isYes: Boolean, default: Boolean = true) {
    data[YesNoStateKey] = isYes
    bold {
        wrapIf(isYes, '[', ']') {
            choiceColor(default)
            text("Yes")
        }
        text(' ')
        wrapIf(!isYes, '[', ']') {
            choiceColor(!default)
            text("No")
        }
        textLine()
    }
}

class YesNoScope(val isYes: Boolean, val shouldAccept: Boolean = false)

// NOTE: This registers onKeyPressed meaning you can't use this AND onKeyPressed in your own code.
// Pass null into `valueOnCancel` to disable cancelling.
fun RunScope.onYesNoChanged(valueOnCancel: Boolean? = false, block: YesNoScope.() -> Unit) {
    fun isYes() = data[YesNoStateKey]!!

    onKeyPressed {
        val yesNoScope = when (key) {
            Keys.LEFT, Keys.RIGHT -> YesNoScope(!isYes())
            Keys.HOME -> YesNoScope(true)
            Keys.END -> YesNoScope(false)

            Keys.Y, Keys.Y_UPPER -> YesNoScope(true, shouldAccept = true)
            Keys.N, Keys.N_UPPER -> YesNoScope(false, shouldAccept = true)

            // Q included because Kobweb users might be used to pressing it in other contexts
            Keys.ESC, Keys.Q, Keys.Q_UPPER -> {
                if (valueOnCancel != null) YesNoScope(valueOnCancel, shouldAccept = true) else null
            }

            Keys.ENTER -> YesNoScope(isYes(), shouldAccept = true)

            else -> null
        }

        yesNoScope?.let { it.block() }
    }
}
