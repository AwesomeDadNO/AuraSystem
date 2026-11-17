package no.aura.os.v2.settings

import no.aura.os.v2.ultramax.UltraCode

/**
 * Pure state rules of the UltraMax code field (typed with the Android TV system keyboard, or pasted). No Android types, no logging; the
 * code only ever lives in the caller's text state.
 */
object CodeInput {
    /** Full Android TV remote fallback alphabet: all 26 letters and ten digits. */
    const val REMOTE_KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** Characters a keyboard or paste may legitimately add around a code (spaces, dashes, line breaks) and that are silently dropped. */
    private fun isSeparator(c: Char) = c.isWhitespace() || c == '-' || c == '_'

    /** Uppercases, drops separators and any other character, caps at [length]. */
    fun normalize(raw: String, length: Int = UltraCode.LENGTH): String =
        raw.uppercase().filter(UltraCode::isValidChar).take(length)

    /** True when [raw] contained something that is neither a code character nor a separator (e.g. "!", "é"): the user is told. */
    fun hadRejected(raw: String): Boolean = raw.any { !UltraCode.isValidChar(it.uppercaseChar()) && !isSeparator(it) }

    /** Upper bound on what the text field may hold (a pasted code with separators is far shorter); longer input is ignored. */
    const val MAX_RAW = 32

    /**
     * What is wrong with the RAW field text (exactly as typed or pasted), or null. The field is never rewritten while the keyboard is composing,
     * so problems are reported instead of silently "fixed": an invalid character, or more code characters than the code has.
     */
    fun problem(raw: String, length: Int = UltraCode.LENGTH): String? = when {
        hadRejected(raw) -> "Only letters A–Z and digits 0–9 are allowed."
        raw.count { UltraCode.isValidChar(it.uppercaseChar()) } > length -> "Too many characters: the code has exactly $length."
        else -> null
    }

    fun isComplete(text: String, length: Int = UltraCode.LENGTH) = text.length == length && text.all(UltraCode::isValidChar)

    /** Plain-language helper line under the field. */
    fun message(text: String, rejected: Boolean, length: Int = UltraCode.LENGTH): String = when {
        rejected -> "Only letters A–Z and digits 0–9 are allowed."
        text.isEmpty() -> "Enter the $length-character code (letters and digits)."
        text.length < length -> "${text.length} of $length characters."
        else -> "Code complete. Choose Save."
    }
}
