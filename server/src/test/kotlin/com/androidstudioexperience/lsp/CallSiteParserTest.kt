package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.CallSiteParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CallSiteParserTest {

    private fun parseAt(text: String, marker: String = "|"): CallSiteParser.CallSite? {
        val offset = text.indexOf(marker)
        require(offset >= 0) { "marker '$marker' not found in text" }
        return CallSiteParser.parse(text.removeRange(offset, offset + marker.length), offset)
    }

    @Test
    fun `cursor right after open paren`() {
        val call = parseAt("""fun x() { greet(|) }""")
        assertNotNull(call)
        assertEquals("greet", call.calleeName)
        assertEquals(0, call.activeParameter)
    }

    @Test
    fun `active parameter counts top-level commas`() {
        val call = parseAt("""fun x() { greet(a, b, |) }""")
        assertNotNull(call)
        assertEquals("greet", call.calleeName)
        assertEquals(2, call.activeParameter)
    }

    @Test
    fun `nested calls resolve to innermost call`() {
        val call = parseAt("""fun x() { outer(inner(1, 2), |) }""")
        assertNotNull(call)
        assertEquals("outer", call.calleeName)
        assertEquals(1, call.activeParameter)
    }

    @Test
    fun `cursor inside nested call args`() {
        val call = parseAt("""fun x() { outer(inner(1, |)) }""")
        assertNotNull(call)
        assertEquals("inner", call.calleeName)
        assertEquals(1, call.activeParameter)
    }

    @Test
    fun `member call resolves simple name`() {
        val call = parseAt("""fun x() { repo.load(id, |) }""")
        assertNotNull(call)
        assertEquals("load", call.calleeName)
        assertEquals(1, call.activeParameter)
    }

    @Test
    fun `constructor call`() {
        val call = parseAt("""val u = User(name, |)""")
        assertNotNull(call)
        assertEquals("User", call.calleeName)
        assertEquals(1, call.activeParameter)
    }

    @Test
    fun `generic call`() {
        val call = parseAt("""fun x() { listOf<String>(a, |) }""")
        assertNotNull(call)
        assertEquals("listOf", call.calleeName)
        assertEquals(1, call.activeParameter)
    }

    @Test
    fun `keywords are not callables`() {
        assertNull(parseAt("""fun x() { if (|) {} }"""))
        assertNull(parseAt("""fun x() { when (|) {} }"""))
    }

    @Test
    fun `parens inside strings and comments are ignored`() {
        val text = """fun x() { greet("a,b", /* (,, */ c, |) }"""
        val call = parseAt(text)
        assertNotNull(call)
        assertEquals("greet", call.calleeName)
        assertEquals(2, call.activeParameter)
    }

    @Test
    fun `lambda argument commas do not count`() {
        val call = parseAt("""fun x() { items.fold({ a, b -> a }, |) }""")
        assertNotNull(call)
        assertEquals("fold", call.calleeName)
        assertEquals(1, call.activeParameter)
    }

    @Test
    fun `no call enclosing cursor`() {
        assertNull(parseAt("""val x = 1 + |2"""))
    }
}
