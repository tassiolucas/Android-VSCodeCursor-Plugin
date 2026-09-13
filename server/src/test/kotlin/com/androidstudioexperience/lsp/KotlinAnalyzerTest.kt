package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.KotlinAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KotlinAnalyzerTest {

    private val sample = """
        package com.example.demo

        /**
         * Greets a person.
         */
        fun greet(name: String): String {
            return "Hello, ${'$'}name!"
        }

        class Greeter {
            fun sayHello(): String = greet("World")
        }

        fun main() {
            val g = Greeter()
            println(g.sayHello())
        }
    """.trimIndent()

    @Test
    fun `hover shows signature and doc`() {
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened("file:///tmp/Hello.kt", sample)
        // "greet" declaration is at line 5 (0-based), col 4
        val hover = analyzer.hover("file:///tmp/Hello.kt", sample, 5, 4)
        assertNotNull(hover, "hover should find greet")
        assertEquals(
            "```kotlin\nfun greet(name: String): String\n```\n\nGreets a person.\n\n_Declared in_ `Hello.kt`:6",
            hover
        )
    }

    @Test
    fun `definition resolves call to declaration`() {
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened("file:///tmp/Hello.kt", sample)
        // "greet(" call inside sayHello at line 10, col 31
        val loc = analyzer.definition("file:///tmp/Hello.kt", sample, 10, 31)
        assertNotNull(loc, "definition should resolve greet call")
        assertEquals(5, loc.range.start.line)
    }

    @Test
    fun `documentSymbols lists all declarations`() {
        val names = KotlinAnalyzer().documentSymbols(sample).map { it.name }
        // greet, Greeter, sayHello, main, plus the local `val g` inside main.
        assertEquals(listOf("greet", "Greeter", "sayHello", "main", "g"), names)
    }
}