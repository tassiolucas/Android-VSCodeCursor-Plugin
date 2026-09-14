package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.KotlinParam
import com.androidstudioexperience.lsp.index.RocksDbIndex
import com.androidstudioexperience.lsp.index.SymbolInfo
import org.eclipse.lsp4j.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RocksDbIndexTest {

    private fun tempIndex(): RocksDbIndex {
        val dir = kotlin.io.path.createTempDirectory("ase-rocksdb-test").toFile()
        return RocksDbIndex.openOrCreate(dir.absolutePath)
            ?: error("RocksDB failed to open in test")
    }

    @Test
    fun `schema v2 symbol round-trips with full contract`() {
        val index = tempIndex()
        try {
            val sym = SymbolInfo(
                id = SymbolInfo.makeId("/tmp/Repo.kt", 3, "Repo.load"),
                name = "load",
                kind = SymbolKind.Function,
                filePath = "/tmp/Repo.kt",
                line = 3,
                col = 8,
                containerName = "Repo",
                moduleName = "core",
                signature = "suspend fun load(id: Long): Flow<Data>",
                doc = "Loads data.",
                parameters = listOf(KotlinParam("id", "Long", null), KotlinParam("opt", "Map<String, Any>", "emptyMap()")),
                returnType = "Flow<Data>",
                superTypes = emptyList(),
                packageName = "com.example",
                visibility = "public",
            )
            index.putSymbol(sym)

            val found = index.findByName("load").singleOrNull()
            assertNotNull(found)
            assertEquals(sym.signature, found.signature)
            assertEquals(sym.doc, found.doc)
            assertEquals(sym.parameters, found.parameters)
            assertEquals(sym.returnType, found.returnType)
            assertEquals(sym.containerName, found.containerName)
            assertEquals(sym.moduleName, found.moduleName)
            assertEquals(sym.packageName, found.packageName)
            assertEquals(sym.visibility, found.visibility)
        } finally {
            index.close()
        }
    }

    @Test
    fun `superTypes survive round-trip for implementations query`() {
        val index = tempIndex()
        try {
            val sym = SymbolInfo(
                id = SymbolInfo.makeId("/tmp/Impl.kt", 1, "Impl"),
                name = "Impl",
                kind = SymbolKind.Class,
                filePath = "/tmp/Impl.kt",
                line = 1,
                col = 6,
                superTypes = listOf("Repo", "Serializable"),
            )
            index.putSymbol(sym)
            val found = index.findByName("Impl").single()
            assertEquals(listOf("Repo", "Serializable"), found.superTypes)
            assertTrue(index.findAllSymbols().any { it.name == "Impl" })
        } finally {
            index.close()
        }
    }
}
