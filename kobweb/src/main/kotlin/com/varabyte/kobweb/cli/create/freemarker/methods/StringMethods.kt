package com.varabyte.kobweb.cli.create.freemarker.methods

import com.varabyte.kobweb.cli.common.Validations
import org.jetbrains.annotations.ApiStatus

class IsNotEmptyMethod : SingleArgMethodModel() {
    override fun exec(value: String): String? {
        return Validations.isNotEmpty(value)
    }
}

@ApiStatus.AvailableSince("0.9.22")
class KebabCaseMethod : SingleArgMethodModel() {
    override fun exec(value: String): String {
        return value.lowercase().split(" ").joinToString("-")
    }
}

@ApiStatus.AvailableSince("0.9.22")
class LowercaseMethod : SingleArgMethodModel() {
    override fun exec(value: String): String {
        return value.lowercase()
    }
}
