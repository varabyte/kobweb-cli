package com.varabyte.kobweb.cli.create.freemarker.methods

class IsYesNoMethod : SingleArgMethodModel() {
    override fun exec(value: String): String? {
        val valueLower = value.lowercase()
        return if (listOf("yes", "no", "true", "false").any { it.startsWith(valueLower) }) {
            null
        } else {
            "Answer must be yes or no"
        }
    }
}

class YesNoToBoolMethod : SingleArgMethodModel() {
    override fun exec(value: String): String {
        val valueLower = value.lowercase()
        return (listOf("yes", "true").any { it.startsWith(valueLower) }).toString()
    }
}

class IsIntMethod : SingleArgMethodModel() {
    override fun exec(value: String): String? {
        return if (value.toIntOrNull() != null) {
            null
        } else {
            "Answer must be an integer number"
        }
    }
}

class IsPositiveIntMethod : SingleArgMethodModel() {
    override fun exec(value: String): String? {
        return if (value.toIntOrNull()?.takeIf { it >= 0 } != null) {
            null
        } else {
            "Answer must be a positive integer number"
        }
    }
}

class IsNumberMethod : SingleArgMethodModel() {
    override fun exec(value: String): String? {
        return if (value.toDoubleOrNull()?.takeIf { it.isFinite() } != null) {
            null
        } else {
            "Answer must be a number"
        }
    }
}

class IsPositiveNumberMethod : SingleArgMethodModel() {
    override fun exec(value: String): String? {
        return if (value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } != null) {
            null
        } else {
            "Answer must be a positive number"
        }
    }
}

class NotMethod : SingleArgMethodModel() {
    override fun exec(value: String): String {
        return (!value.toBoolean()).toString()
    }
}
