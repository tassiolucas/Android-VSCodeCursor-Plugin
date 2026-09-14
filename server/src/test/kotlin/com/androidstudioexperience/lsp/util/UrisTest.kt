package com.androidstudioexperience.lsp.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UrisTest {

    @Test
    fun `linux absolute path keeps leading slash`() {
        assertEquals(
            "/home/user/Projetos/KMP App/Main.kt",
            Uris.uriToPath("file:///home/user/Projetos/KMP%20App/Main.kt")
        )
    }

    @Test
    fun `uri without file scheme is returned as-is`() {
        assertEquals("file://host/share/x.kt", Uris.uriToPath("file://host/share/x.kt"))
    }

    @Test
    fun `percent-encoded characters are decoded`() {
        assertEquals("/tmp/a b/c.kt", Uris.uriToPath("file:///tmp/a%20b/c.kt"))
    }

    @Test
    fun `malformed uri returns null`() {
        assertEquals(null, Uris.uriToPath("file:///%zz"))
    }
}