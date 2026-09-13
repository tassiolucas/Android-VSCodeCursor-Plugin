package com.androidstudioexperience.lsp.documents

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the current text of every open document, with incremental sync.
 */
class DocumentManager {

    private val documents = ConcurrentHashMap<String, String>()

    fun open(uri: String, text: String) {
        documents[uri] = text
    }

    fun change(uri: String, range: Range?, text: String) {
        val current = documents[uri] ?: return
        if (range == null) {
            // Full sync
            documents[uri] = text
        } else {
            documents[uri] = applyEdit(current, range, text)
        }
    }

    fun close(uri: String) {
        documents.remove(uri)
    }

    fun get(uri: String): String? = documents[uri]

    fun getAll(): Map<String, String> = documents.toMap()

    fun clear() = documents.clear()

    /** Convert a zero-based LSP position into a character offset in the current text. */
    fun offsetAt(uri: String, position: Position): Int {
        val text = documents[uri] ?: return 0
        return offsetOf(text, position.line, position.character)
    }

    private fun offsetOf(text: String, line: Int, character: Int): Int {
        var currentLine = 0
        var offset = 0
        while (currentLine < line && offset < text.length) {
            val nl = text.indexOf('\n', offset)
            if (nl == -1) return text.length
            offset = nl + 1
            currentLine++
        }
        return minOf(offset + character, text.length)
    }

    private fun applyEdit(text: String, range: Range, replacement: String): String {
        val start = offsetOf(text, range.start.line, range.start.character)
        val end = offsetOf(text, range.end.line, range.end.character)
        return text.substring(0, start) + replacement + text.substring(end)
    }

    fun lineText(uri: String, line: Int): String {
        val text = documents[uri] ?: return ""
        val lines = text.lines()
        return if (line in lines.indices) lines[line] else ""
    }

    fun uriToPath(uri: String): String? = try {
        Paths.get(URI.create(uri)).toAbsolutePath().toString()
    } catch (e: Exception) {
        null
    }
}