package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.KotlinAnalyzer
import com.androidstudioexperience.lsp.analysis.SemanticTokensProvider
import com.androidstudioexperience.lsp.documents.DocumentManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTokensProviderTest {

    private val sample = """
        package com.example.demo

        // Greets a person
        @Composable
        fun greet(name: String): String {
            return "Hello, " + name
        }

        data class User(val name: String, val age: Int)

        abstract class BaseRepo {
            abstract suspend fun loadMapped(ids: List<Long>): Map<Long, String>
            internal val cached: MutableMap<Long, String> = mutableMapOf()
        }

        fun main() {
            val g = User("a", 30)
            println(g.name)
            val n = 42
        }
    """.trimIndent()

    private fun tokensOf(uri: String, text: String): List<Triple<Int, Int, Int>> {
        val documents = DocumentManager()
        documents.open(uri, text)
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened(uri, text)
        val provider = SemanticTokensProvider(documents, analyzer, { null }, { null })
        val data = provider.computeFull(uri).data

        // Decode delta-encoded tokens into (line, col, type).
        val out = mutableListOf<Triple<Int, Int, Int>>()
        var line = 0
        var col = 0
        var i = 0
        while (i + 4 < data.size) {
            val dl = data[i]
            val dc = data[i + 1]
            val len = data[i + 2]
            val type = data[i + 3]
            val mods = data[i + 4]
            line += dl
            col = if (dl == 0) col + dc else dc
            out.add(Triple(line, col, type))
            i += 5
        }
        return out
    }

    @Test
    fun `emits tokens for keywords, strings, comments and numbers`() {
        val tokens = tokensOf("file:///tmp/Demo.kt", sample)
        val types = tokens.map { it.third }.toSet()
        assertTrue(SemanticTokensProvider.KEYWORD in types, "keywords")
        assertTrue(SemanticTokensProvider.STRING in types, "strings")
        assertTrue(SemanticTokensProvider.COMMENT in types, "comments")
        assertTrue(SemanticTokensProvider.NUMBER in types, "numbers")
        assertTrue(SemanticTokensProvider.DECORATOR in types, "annotations")
    }

    @Test
    fun `resolves declarations and types by name`() {
        val uri = "file:///tmp/Demo.kt"
        val tokens = tokensOf(uri, sample)
        val lines = sample.lines()

        fun tokenTypeAt(word: String): Int? {
            // find the first occurrence line/col of the word and look up token
            for (i in lines.indices) {
                val c = lines[i].indexOf(word)
                if (c >= 0) {
                    val t = tokens.firstOrNull { it.first == i && it.second == c }
                    if (t != null) return t.third
                }
            }
            return null
        }

        assertEquals(SemanticTokensProvider.CLASS, tokenTypeAt("User"), "data class declaration -> class")
        assertEquals(SemanticTokensProvider.CLASS, tokenTypeAt("BaseRepo"), "abstract class -> class")
        assertEquals(SemanticTokensProvider.FUNCTION, tokenTypeAt("loadMapped"), "abstract suspend fun -> function")
        assertEquals(SemanticTokensProvider.PROPERTY, tokenTypeAt("cached"), "internal val -> property")
    }

    @Test
    fun `uppercase heuristic colors unknown types as class`() {
        val text = "val x: SomeUnknownType = TODO()"
        val tokens = tokensOf("file:///tmp/H.kt", text)
        val lines = text.lines()
        val c = lines[0].indexOf("SomeUnknownType")
        val token = tokens.firstOrNull { it.first == 0 && it.second == c }
        assertEquals(SemanticTokensProvider.CLASS, token?.third, "uppercase unknown -> class")
    }
}