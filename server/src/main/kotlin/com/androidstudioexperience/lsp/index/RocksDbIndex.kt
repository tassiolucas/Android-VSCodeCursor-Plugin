package com.androidstudioexperience.lsp.index

import org.eclipse.lsp4j.SymbolKind
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.io.File

/**
 * RocksDB-backed symbol index for fast cross-file lookups.
 *
 * Simplified version using single column family to avoid RocksDB API complexity.
 * All keys are prefixed by column family name:
 * - "sym:{symbolId}" -> JSON(SymbolInfo)
 * - "name:{name}" -> comma-separated symbolIds
 * - "ref:{symbolId}" -> JSON(List<SymbolReference>)
 * - "meta:{key}" -> value
 */
class RocksDbIndex private constructor(
    private val db: RocksDB
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val logger = LoggerFactory.getLogger(RocksDbIndex::class.java)

        /**
         * Opens or creates a RocksDB index at the given path.
         * Returns null if RocksDB fails to load (native library issue).
         */
        fun openOrCreate(path: String): RocksDbIndex? {
            try {
                RocksDB.loadLibrary()
            } catch (e: Exception) {
                logger.error("Failed to load RocksDB native library: ${e.message}")
                return null
            }

            val dbPath = File(path)
            logger.info("RocksDB path: ${dbPath.absolutePath}")
            
            // Destroy existing DB if present
            if (dbPath.exists()) {
                logger.info("Destroying existing RocksDB")
                try {
                    RocksDB.destroyDB(dbPath.absolutePath, Options())
                } catch (e: Exception) {
                    logger.warn("Failed to destroy RocksDB: ${e.message}")
                    dbPath.deleteRecursively()
                }
            }
            dbPath.mkdirs()

            // Open simple single-CF database
            val options = Options().setCreateIfMissing(true)
            val db = RocksDB.open(options, dbPath.absolutePath)
            logger.info("Opened RocksDB at ${dbPath.absolutePath}")

            return RocksDbIndex(db)
        }

        private fun key(prefix: String, key: String) = "$prefix:$key".toByteArray(Charsets.UTF_8)
    }

    /**
     * Stores a symbol in the index.
     */
    fun putSymbol(symbol: SymbolInfo) {
        val json = symbolToJson(symbol)
        db.put(key("sym", symbol.id), json.toByteArray(Charsets.UTF_8))

        // Update name index (append symbolId)
        val existing = db.get(key("name", symbol.name))
        val symbolIds = if (existing != null) {
            String(existing, Charsets.UTF_8).split(",").toMutableSet()
        } else {
            mutableSetOf()
        }
        symbolIds.add(symbol.id)
        db.put(key("name", symbol.name), symbolIds.joinToString(",").toByteArray(Charsets.UTF_8))

        logger.debug("Indexed symbol: ${symbol.id}")
    }

    /**
     * Retrieves a symbol by its ID.
     */
    fun getSymbol(symbolId: String): SymbolInfo? {
        val json = db.get(key("sym", symbolId)) ?: return null
        val parsed = jsonToSymbol(String(json, Charsets.UTF_8))
        if (parsed == null) {
            logger.warn("getSymbol: failed to parse JSON for '$symbolId': ${String(json, Charsets.UTF_8).take(120)}")
        }
        return parsed
    }

    /**
     * Finds all symbols with a given name.
     */
    fun findByName(name: String): List<SymbolInfo> {
        val existing = db.get(key("name", name)) ?: return emptyList()
        val symbolIds = String(existing, Charsets.UTF_8).split(",")
        return symbolIds.mapNotNull { id -> getSymbol(id) }
    }

    /**
     * Finds symbols by partial name match (prefix or contains).
     */
    fun findByPartialName(query: String, maxResults: Int = 50): List<SymbolInfo> {
        val results = mutableListOf<SymbolInfo>()
        val iterator = db.newIterator()
        iterator.seekToFirst()
        
        val queryLower = query.lowercase()
        var totalKeys = 0
        var nameKeys = 0
        
        while (iterator.isValid() && results.size < maxResults) {
            val dbKey = String(iterator.key(), Charsets.UTF_8)
            totalKeys++
            if (dbKey.startsWith("name:")) {
                nameKeys++
                val name = dbKey.removePrefix("name:")
                if (name.lowercase().contains(queryLower)) {
                    val symbolIds = String(iterator.value(), Charsets.UTF_8).split(",")
                    val symbols = symbolIds.mapNotNull { id -> getSymbol(id) }
                    results.addAll(symbols.take(maxResults - results.size))
                }
            }
            iterator.next()
        }
        iterator.close()
        logger.info("findByPartialName('$query'): scanned $totalKeys keys, $nameKeys name: keys, returned ${results.size}")
        return results
    }

    /**
     * Adds a reference (usage) to a symbol.
     */
    fun addReference(symbolId: String, reference: SymbolReference) {
        val existing = db.get(key("ref", symbolId))
        val refs = if (existing != null) {
            jsonToReferences(String(existing, Charsets.UTF_8)).toMutableList()
        } else {
            mutableListOf()
        }
        refs.add(reference)
        db.put(key("ref", symbolId), referencesToJson(refs).toByteArray(Charsets.UTF_8))
    }

    /**
     * Gets all references (usages) of a symbol.
     */
    fun getReferences(symbolId: String): List<SymbolReference> {
        val json = db.get(key("ref", symbolId)) ?: return emptyList()
        return jsonToReferences(String(json, Charsets.UTF_8))
    }

    /**
     * Clears all indexed data.
     */
    fun clear() {
        val iterator = db.newIterator()
        iterator.seekToFirst()
        val keys = mutableListOf<ByteArray>()
        while (iterator.isValid()) {
            keys.add(iterator.key())
            iterator.next()
        }
        iterator.close()
        for (key in keys) {
            db.delete(key)
        }
        logger.info("Cleared all indexed data")
    }

    /**
     * Sets metadata value.
     */
    fun setMeta(key: String, value: String) {
        db.put(key("meta", key), value.toByteArray(Charsets.UTF_8))
    }

    /**
     * Gets metadata value.
     */
    fun getMeta(key: String): String? {
        return String(db.get(key("meta", key)) ?: return null, Charsets.UTF_8)
    }

    /**
     * Closes the database.
     */
    fun close() {
        try {
            db.close()
        } catch (e: Exception) {
            logger.error("Error closing RocksDB: ${e.message}")
        }
    }

    // Simple JSON serialization (no external deps)
    private fun symbolToJson(symbol: SymbolInfo): String {
        return buildString {
            append("{")
            append("\"id\":\"${escapeJson(symbol.id)}\",")
            append("\"name\":\"${escapeJson(symbol.name)}\",")
            append("\"kind\":${symbol.kind.value},")
            append("\"filePath\":\"${escapeJson(symbol.filePath)}\",")
            append("\"line\":${symbol.line},")
            append("\"col\":${symbol.col},")
            if (symbol.containerName != null) append("\"containerName\":\"${escapeJson(symbol.containerName)}\",")
            if (symbol.moduleName != null) append("\"moduleName\":\"${escapeJson(symbol.moduleName)}\",")
            append("}")
        }
    }

    private fun jsonToSymbol(json: String): SymbolInfo? {
        fun extractString(k: String): String? {
            val pattern = "\"$k\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            val raw = pattern.find(json)?.groupValues?.get(1) ?: return null
            // Unescape JSON escapes: \\ -> \, \" -> "
            return raw.replace("\\\\", "\\").replace("\\\"", "\"")
        }
        fun extractInt(k: String): Int {
            val pattern = "\"$k\"\\s*:\\s*(\\d+)".toRegex()
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        val id = extractString("id") ?: return null
        val name = extractString("name") ?: return null
        val kindValue = extractInt("kind")
        val filePath = extractString("filePath") ?: return null
        val line = extractInt("line")
        val col = extractInt("col")
        val containerName = extractString("containerName")
        val moduleName = extractString("moduleName")

        val kind = SymbolKind.values().find { it.value == kindValue } ?: SymbolKind.Variable

        return SymbolInfo(id, name, kind, filePath, line, col, containerName, moduleName)
    }

    private fun referencesToJson(refs: List<SymbolReference>): String {
        return refs.joinToString(prefix = "[", postfix = "]") { ref ->
            buildString {
                append("{")
                append("\"symbolId\":\"${escapeJson(ref.symbolId)}\",")
                append("\"filePath\":\"${escapeJson(ref.filePath)}\",")
                append("\"line\":${ref.line},")
                append("\"col\":${ref.col},")
                append("\"referenceText\":\"${escapeJson(ref.referenceText)}\"")
                append("}")
            }
        }
    }

    private fun jsonToReferences(json: String): List<SymbolReference> {
        val refs = mutableListOf<SymbolReference>()
        val pattern = "\\{[^}]+\\}".toRegex()
        for (match in pattern.findAll(json)) {
            val obj = match.value
            fun extractString(k: String): String? {
                val p = "\"$k\"\\s*:\\s*\"([^\"]*)\"".toRegex()
                val raw = p.find(obj)?.groupValues?.get(1) ?: return null
                return raw.replace("\\\\", "\\").replace("\\\"", "\"")
            }
            fun extractInt(k: String): Int {
                val p = "\"$k\"\\s*:\\s*(\\d+)".toRegex()
                return p.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }

            val symbolId = extractString("symbolId") ?: continue
            val filePath = extractString("filePath") ?: continue
            val line = extractInt("line")
            val col = extractInt("col")
            val refText = extractString("referenceText") ?: ""

            refs.add(SymbolReference(symbolId, filePath, line, col, refText))
        }
        return refs
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}