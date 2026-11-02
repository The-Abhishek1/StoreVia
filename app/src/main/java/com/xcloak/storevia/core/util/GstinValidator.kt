package com.xcloak.storevia.core.util

/** Validates the 15-character GSTIN: format, state code and the mod-36 check character. */
object GstinValidator {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val FORMAT = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")

    enum class Result { VALID, BAD_FORMAT, STATE_MISMATCH, BAD_CHECKSUM }

    fun normalize(input: String) = input.trim().uppercase()

    fun validate(input: String, stateCode: String): Result {
        val g = normalize(input)
        if (!FORMAT.matches(g)) return Result.BAD_FORMAT
        if (!g.startsWith(stateCode)) return Result.STATE_MISMATCH
        return if (checkChar(g.substring(0, 14)) == g[14]) Result.VALID else Result.BAD_CHECKSUM
    }

    fun checkChar(first14: String): Char {
        var sum = 0
        first14.forEachIndexed { i, c ->
            val product = CHARS.indexOf(c) * (if (i % 2 == 0) 1 else 2)
            sum += product / 36 + product % 36
        }
        return CHARS[(36 - sum % 36) % 36]
    }
}
