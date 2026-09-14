package com.androidstudioexperience.lsp.analysis

/**
 * Locates the function call enclosing the cursor and computes the active
 * parameter index — the structural equivalent of Android Studio's
 * "Parameter Info" (Ctrl+P).
 *
 * Works on masked text (comments/strings blanked via [KotlinMask]) so
 * parentheses inside literals never confuse the depth counting.
 */
object CallSiteParser {

    data class CallSite(
        val calleeName: String,
        val activeParameter: Int,
        val openParenOffset: Int,
    )

    private val NON_CALLABLE = setOf(
        "if", "when", "for", "while", "catch", "return", "constructor",
        "super", "this", "init", "class", "object", "interface", "fun",
        "do", "try", "else", "switch", "synchronized", "throw",
    )

    /**
     * Parses the call enclosing [offset] in [text] (offset = absolute char
     * offset of the cursor). Returns null when the cursor is not inside a
     * call's argument list.
     */
    fun parse(text: String, offset: Int): CallSite? {
        val masked = KotlinMask.maskLines(text).joinToString("\n")
        if (offset < 0 || offset > masked.length) return null

        // 1. Scan backwards to find the unmatched '(' enclosing the cursor.
        var depth = 0
        var open = -1
        var i = offset - 1
        while (i >= 0) {
            when (masked[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) { open = i; break }
                    depth--
                }
            }
            i--
        }
        if (open == -1) return null

        // 2. The callee is the identifier immediately before the '('.
        var j = open - 1
        while (j >= 0 && masked[j].isWhitespace()) j--
        if (j < 0) return null
        // Skip a generic argument list: foo<T, R>(...)
        if (masked[j] == '>') {
            var angle = 0
            while (j >= 0) {
                when (masked[j]) {
                    '>' -> angle++
                    '<' -> { angle--; if (angle == 0) break }
                }
                j--
            }
            j-- // move past the '<'
            while (j >= 0 && masked[j].isWhitespace()) j--
            if (j < 0) return null
        }
        if (!(masked[j].isLetterOrDigit() || masked[j] == '_')) return null
        var start = j
        while (start > 0 && (masked[start - 1].isLetterOrDigit() || masked[start - 1] == '_')) start--
        val name = masked.substring(start, j + 1)
        if (name in NON_CALLABLE) return null

        // 3. Active parameter = top-level commas between '(' and the cursor.
        var paren = 0; var bracket = 0; var brace = 0; var angle = 0
        var commas = 0
        var k = open + 1
        while (k < offset) {
            when (masked[k]) {
                '(' -> paren++
                ')' -> paren--
                '[' -> bracket++
                ']' -> bracket--
                '{' -> brace++
                '}' -> brace--
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                ',' -> if (paren == 0 && bracket == 0 && brace == 0 && angle == 0) commas++
            }
            k++
        }

        return CallSite(name, commas, open)
    }
}
