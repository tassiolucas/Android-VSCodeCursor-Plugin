package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.JavaAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaAnalyzerTest {

    @Test
    fun `parses java class with members`() {
        val text = """
            package com.example;

            /** A repo. */
            public class UserRepo extends BaseRepo implements Closeable {
                private final Database db;

                public UserRepo(Database db) {
                    this.db = db;
                }

                public String load(long id, boolean fresh) {
                    return "";
                }

                @Override
                public void close() {}
            }
        """.trimIndent()

        val top = JavaAnalyzer().topLevelSymbols(text)
        assertEquals(1, top.size)
        val cls = top[0]
        assertEquals("UserRepo", cls.name)
        assertEquals(listOf("BaseRepo", "Closeable"), cls.superTypes)
        assertEquals("com.example", cls.packageName)
        assertEquals("public", cls.visibility)
        assertEquals("A repo.", cls.doc)

        val members = cls.children
        val db = members.firstOrNull { it.name == "db" }
        assertNotNull(db)
        assertEquals("Database", db.returnType)

        val ctor = members.firstOrNull { it.kind == org.eclipse.lsp4j.SymbolKind.Constructor }
        assertNotNull(ctor)
        assertEquals(listOf("db"), ctor.parameters.map { it.name })
        assertEquals("Database", ctor.parameters[0].type)

        val load = members.firstOrNull { it.name == "load" }
        assertNotNull(load)
        assertEquals(org.eclipse.lsp4j.SymbolKind.Method, load.kind)
        assertEquals("String", load.returnType)
        assertEquals(listOf("id", "fresh"), load.parameters.map { it.name })
        assertEquals("long", load.parameters[0].type)
        assertEquals("boolean", load.parameters[1].type)

        assertTrue(members.any { it.name == "close" })
    }

    @Test
    fun `parses enum with constants`() {
        val text = """
            package com.example;

            public enum State {
                IDLE,
                LOADING,
                ERROR;

                public String label() { return name(); }
            }
        """.trimIndent()
        val top = JavaAnalyzer().topLevelSymbols(text)
        val state = top.single { it.name == "State" }
        assertEquals(org.eclipse.lsp4j.SymbolKind.Enum, state.kind)
        val entries = state.children.filter { it.kind == org.eclipse.lsp4j.SymbolKind.EnumMember }.map { it.name }
        assertEquals(listOf("IDLE", "LOADING", "ERROR"), entries)
        assertTrue(state.children.any { it.name == "label" })
    }

    @Test
    fun `parses interface`() {
        val text = """
            package com.example;
            public interface Repo {
                String find(long id);
            }
        """.trimIndent()
        val top = JavaAnalyzer().topLevelSymbols(text)
        val repo = top.single { it.name == "Repo" }
        assertEquals(org.eclipse.lsp4j.SymbolKind.Interface, repo.kind)
        assertEquals("find", repo.children[0].name)
    }
}
