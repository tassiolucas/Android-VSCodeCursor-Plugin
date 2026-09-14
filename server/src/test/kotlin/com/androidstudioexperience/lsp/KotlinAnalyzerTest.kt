package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.KotlinAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `documentSymbols lists all declarations hierarchically`() {
        fun flatten(symbols: List<org.eclipse.lsp4j.DocumentSymbol>): List<org.eclipse.lsp4j.DocumentSymbol> =
            symbols.flatMap { listOf(it) + flatten(it.children.orEmpty()) }

        val names = flatten(KotlinAnalyzer().documentSymbols(sample)).map { it.name }
        // greet, Greeter, main at top level; sayHello is a member of Greeter.
        // Local vals inside function bodies are intentionally not collected.
        assertEquals(listOf("greet", "Greeter", "sayHello", "main"), names)
    }

    @Test
    fun `parses declarations with modifiers and annotations`() {
        val text = """
            package com.example.demo

            import androidx.compose.runtime.Composable

            @Composable
            fun ComposableScreen() {}

            @Composable fun InlineAnnotated() {}

            data class User(val name: String, val age: Int)

            abstract class BaseRepository {
                abstract suspend fun loadMapped(ids: List<Long>): Map<Long, String>
                internal val cached: MutableMap<Long, String> = mutableMapOf()
            }

            private class Impl : BaseRepository() {
                override fun loadMapped(ids: List<Long>): Map<Long, String> = emptyMap()
            }

            private var database: AppDatabase? = null

            fun String.customExt(): String = this

            internal object Factory {
                fun create(): BaseRepository = Impl()
            }

            enum class Category { A, B }

            class DataRepo(
                private val dao: FruittieDao,
                private var conn: Connection,
            )
        """.trimIndent()

        val names = KotlinAnalyzer().allDeclarations(text).map { it.name }
        for (expected in listOf(
            "ComposableScreen", "InlineAnnotated", "User", "BaseRepository",
            "loadMapped", "cached", "Impl", "database", "customExt",
            "Factory", "create", "Category", "DataRepo", "dao", "conn",
        )) {
            assertTrue(names.contains(expected), "missing '$expected' in $names")
        }
    }

    @Test
    fun `definitionOf resolves any identifier in-file`() {
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened("file:///tmp/DataRepo.kt", """
            class DataRepo(
                private var database: Database,
            ) {
                fun refresh() = database.loadMapped()
            }
        """.trimIndent())

        val databaseLoc = analyzer.definitionOf("file:///tmp/DataRepo.kt", "database")
        assertNotNull(databaseLoc)
        assertEquals(1, databaseLoc.range.start.line)

        val refreshLoc = analyzer.definitionOf("file:///tmp/DataRepo.kt", "refresh")
        assertNotNull(refreshLoc)
        assertEquals(3, refreshLoc.range.start.line)

        val missing = analyzer.definitionOf("file:///tmp/DataRepo.kt", "nope")
        assertEquals(null, missing)
    }

    // ----- Milestone B: rich structural model ---------------------------------

    @Test
    fun `parses parameters return type and multi-line signature`() {
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened("file:///tmp/Svc.kt", """
            package com.example

            class Service {
                suspend fun load(
                    id: Long,
                    options: Map<String, Any> = emptyMap(),
                ): Flow<Result<Data>> = flow {}
            }
        """.trimIndent())

        val load = analyzer.declarationSymbol("file:///tmp/Svc.kt", "load")
        assertNotNull(load)
        assertEquals("Flow<Result<Data>>", load.returnType)
        assertEquals(listOf("id", "options"), load.parameters.map { it.name })
        assertEquals("Long", load.parameters[0].type)
        assertEquals("emptyMap()", load.parameters[1].default)
        assertEquals("Service", load.containerName)
        assertEquals("Service.load", load.qualifiedName)
        assertEquals("com.example", load.packageName)
    }

    @Test
    fun `parses super types of classes and interfaces`() {
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened("file:///tmp/Repo.kt", """
            interface Repo
            class Impl : Repo, java.io.Serializable {
                constructor(x: Int)
            }
        """.trimIndent())

        val impl = analyzer.declarationSymbol("file:///tmp/Repo.kt", "Impl")
        assertNotNull(impl)
        assertEquals(listOf("Repo", "Serializable"), impl.superTypes)

        // Secondary constructor is indexed under the class name.
        val ctors = analyzer.declarationsByName("file:///tmp/Repo.kt", "Impl")
        assertTrue(ctors.any { it.kind == org.eclipse.lsp4j.SymbolKind.Constructor })
    }

    @Test
    fun `parses typealias and enum entries`() {
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened("file:///tmp/Types.kt", """
            typealias Handler = (String) -> Unit

            enum class Color {
                RED,
                GREEN,
                BLUE;
            }
        """.trimIndent())

        assertNotNull(analyzer.declarationSymbol("file:///tmp/Types.kt", "Handler"))
        for (entry in listOf("RED", "GREEN", "BLUE")) {
            val sym = analyzer.declarationSymbol("file:///tmp/Types.kt", entry)
            assertNotNull(sym, "missing enum entry $entry")
            assertEquals(org.eclipse.lsp4j.SymbolKind.EnumMember, sym.kind)
            assertEquals("Color", sym.containerName)
        }
    }

    @Test
    fun `document symbols are hierarchical with body spans`() {
        val text = """
            class Outer {
                val prop: Int = 1
                fun method() {}
            }
            fun topLevel() {}
        """.trimIndent()
        val top = KotlinAnalyzer().documentSymbols(text)
        assertEquals(listOf("Outer", "topLevel"), top.map { it.name })
        val outer = top[0]
        assertEquals(listOf("prop", "method"), outer.children.map { it.name })
        // Parent range contains member ranges (VSCode outline requirement).
        for (child in outer.children) {
            assertTrue(child.range.start.line >= outer.range.start.line)
            assertTrue(child.range.end.line <= outer.range.end.line)
        }
    }

    @Test
    fun `same-named members of different classes do not collide`() {
        val analyzer = KotlinAnalyzer()
        analyzer.onDocumentOpened("file:///tmp/Two.kt", """
            class A {
                fun render() {}
            }
            class B {
                fun render() {}
            }
        """.trimIndent())
        val renders = analyzer.declarationsByName("file:///tmp/Two.kt", "render")
        assertEquals(2, renders.size)
        assertEquals(setOf("A", "B"), renders.map { it.containerName }.toSet())
    }
}