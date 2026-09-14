package com.androidstudioexperience.lsp.analysis

/**
 * Comment/string masking lexer for Kotlin source.
 *
 * Produces, for every line, a "masked" version with the same length as the
 * original where comment and string/char literal contents are replaced by
 * spaces. Positions (line/col) are therefore preserved, while regex-based
 * structural matching never fires inside comments or string literals.
 *
 * Also used by documentHighlight and find-usages filtering.
 */
object KotlinMask {

    /**
     * Returns masked lines (same count and per-line length as [text]).
     * KDoc/comment text is blanked too — doc association is done separately
     * from the original text.
     */
    fun maskLines(text: String): List<String> {
        val lines = text.lines()
        val out = Array(lines.size) { StringBuilder() }

        var inBlockComment = false
        var inTripleString = false

        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            val masked = out[lineIdx]
            var i = 0
            while (i < line.length) {
                val c = line[i]
                val next = if (i + 1 < line.length) line[i + 1] else ' '
                when {
                    inBlockComment -> {
                        if (c == '*' && next == '/') {
                            masked.append("  ")
                            i += 2
                            inBlockComment = false
                        } else {
                            masked.append(' ')
                            i++
                        }
                    }
                    inTripleString -> {
                        if (c == '"' && next == '"' && i + 2 < line.length && line[i + 2] == '"') {
                            masked.append("   ")
                            i += 3
                            inTripleString = false
                        } else {
                            masked.append(if (c == '\t') '\t' else ' ')
                            i++
                        }
                    }
                    c == '/' && next == '/' -> {
                        repeat(line.length - i) { masked.append(' ') }
                        i = line.length
                    }
                    c == '/' && next == '*' -> {
                        masked.append("  ")
                        i += 2
                        inBlockComment = true
                    }
                    c == '"' && next == '"' && i + 2 < line.length && line[i + 2] == '"' -> {
                        masked.append("   ")
                        i += 3
                        inTripleString = true
                    }
                    c == '"' -> {
                        masked.append('"')
                        i++
                        while (i < line.length && line[i] != '"') {
                            if (line[i] == '\\' && i + 1 < line.length) {
                                masked.append("  ")
                                i += 2
                            } else {
                                masked.append(' ')
                                i++
                            }
                        }
                        if (i < line.length) {
                            masked.append('"')
                            i++
                        }
                    }
                    c == '\'' -> {
                        masked.append('\'')
                        i++
                        while (i < line.length && line[i] != '\'') {
                            if (line[i] == '\\' && i + 1 < line.length) {
                                masked.append("  ")
                                i += 2
                            } else {
                                masked.append(' ')
                                i++
                            }
                        }
                        if (i < line.length) {
                            masked.append('\'')
                            i++
                        }
                    }
                    else -> {
                        masked.append(c)
                        i++
                    }
                }
            }
            // In a block comment / triple string, nothing more to do — the
            // state carries to the next line.
        }
        return out.map { it.toString() }
    }

    /** True when [offset] in [maskedLine] is inside blanked (comment/string) text. */
    fun isCode(maskedLine: String, offset: Int): Boolean =
        offset >= 0 && offset < maskedLine.length && !maskedLine[offset].isWhitespace()
}
