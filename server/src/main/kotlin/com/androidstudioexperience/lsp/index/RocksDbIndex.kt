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
            dbPath.mkdirs()

            return try {
                open(dbPath)
            } catch (e: RocksDBException) {
                // Corruption (e.g. the process was SIGKILLed mid-write, leaving a
                // dangling .sst). Recover by destroying and recreating an empty
                // index — the project is then re-indexed from source.
                if (isCorruption(e)) {
                    logger.warn("Corrupted index detected (${e.message}); recreating from scratch")
                    recover(dbPath)
                } else {
                    // Lock contention or other transient error: do NOT destroy,
                    // another process may own the lock. Fail gracefully.
                    logger.error("Failed to open RocksDB (${e.message})")
                    null
                }
            }
        }

        private fun open(dir: File): RocksDbIndex {
            val db = RocksDB.open(Options().setCreateIfMissing(true), dir.absolutePath)
            logger.info("Opened RocksDB at ${dir.absolutePath}")
            return RocksDbIndex(db)
        }

        private fun isCorruption(e: RocksDBException): Boolean {
            val msg = e.message ?: ""
            return msg.contains("Corruption") || msg.contains("corrupted") || msg.contains("MANIFEST")
        }

        private fun recover(dir: File): RocksDbIndex? {
            return try {
                try { RocksDB.destroyDB(dir.absolutePath, Options()) } catch (_: Exception) {}
                dir.deleteRecursively()
                dir.mkdirs()
                open(dir)
            } catch (e2: Exception) {
                logger.error("Failed to recover RocksDB: ${e2.message}")
                null
            }
        }

        private fun key(prefix: String, key: String) = "$prefix:$key".toByteArray(Charsets.UTF_8)

        fun camelMatch(name: String, query: String): Boolean {
            var qi = 0
            for (c in name) {
                if (qi >= query.length) break
                val q = query[qi]
                val matches = if (q.isUpperCase()) c == q else c.lowercaseChar() == q.lowercaseChar()
                if (matches) qi++
            }
            return qi == query.length
        }
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
     * Returns every project symbol in the index (full scan of the `sym:` space).
     * Used by structural hierarchy queries (implementations, type hierarchy).
     */
    fun findAllSymbols(maxResults: Int = 100000): List<SymbolInfo> {
        val results = mutableListOf<SymbolInfo>()
        val iterator = db.newIterator()
        iterator.seekToFirst()
        while (iterator.isValid() && results.size < maxResults) {
            val k = String(iterator.key(), Charsets.UTF_8)
            if (k.startsWith("sym:")) {
                jsonToSymbol(String(iterator.value(), Charsets.UTF_8))?.let { results.add(it) }
            }
            iterator.next()
        }
        iterator.close()
        return results
    }

    /**
     * CamelCase ("LiSc" -> "LoginScreen") symbol search over the name space.
     * Uppercase query letters must land on uppercase name letters; lowercase
     * query letters match any letter. Empty query matches nothing.
     */
    fun findByCamelCase(query: String, maxResults: Int = 50): List<SymbolInfo> {
        if (query.isEmpty()) return emptyList()
        val results = mutableListOf<SymbolInfo>()
        val iterator = db.newIterator()
        iterator.seekToFirst()
        while (iterator.isValid() && results.size < maxResults) {
            val dbKey = String(iterator.key(), Charsets.UTF_8)
            if (dbKey.startsWith("name:")) {
                val name = dbKey.removePrefix("name:")
                if (camelMatch(name, query)) {
                    val symbolIds = String(iterator.value(), Charsets.UTF_8).split(",")
                    val symbols = symbolIds.mapNotNull { id -> getSymbol(id) }
                    results.addAll(symbols.take(maxResults - results.size))
                }
            }
            iterator.next()
        }
        iterator.close()
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
     * Replaces all references contributed by [filePath] for [symbolId]
     * (incremental re-indexing of a file).
     */
    fun replaceFileReferences(symbolId: String, filePath: String, newRefs: List<SymbolReference>) {
        val k = key("ref", symbolId)
        val existing = db.get(k)
        val refs = if (existing != null) {
            jsonToReferences(String(existing, Charsets.UTF_8)).toMutableList()
        } else {
            mutableListOf()
        }
        refs.removeAll { it.filePath == filePath }
        refs.addAll(newRefs)
        if (refs.isEmpty()) db.delete(k)
        else db.put(k, referencesToJson(refs).toByteArray(Charsets.UTF_8))
    }

    /** Removes every stored reference contributed by [filePath] (file deleted). */
    fun removeRefsOfFile(filePath: String) {
        val iterator = db.newIterator()
        iterator.seekToFirst()
        val updates = mutableListOf<Pair<ByteArray, List<SymbolReference>>>()
        while (iterator.isValid()) {
            val k = String(iterator.key(), Charsets.UTF_8)
            if (k.startsWith("ref:")) {
                val refs = jsonToReferences(String(iterator.value(), Charsets.UTF_8))
                val remaining = refs.filter { it.filePath != filePath }
                if (remaining.size != refs.size) {
                    updates.add(iterator.key().copyOf() to remaining)
                }
            }
            iterator.next()
        }
        iterator.close()
        for ((k, remaining) in updates) {
            if (remaining.isEmpty()) db.delete(k)
            else db.put(k, referencesToJson(remaining).toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Gets all references (usages) of a symbol.
     */
    fun getReferences(symbolId: String): List<SymbolReference> {
        val json = db.get(key("ref", symbolId)) ?: return emptyList()
        return jsonToReferences(String(json, Charsets.UTF_8))
    }

    /**
     * Removes every symbol indexed from the given source file (and cleans the
     * name index). Used for incremental re-indexing of changed files.
     */
    fun removeFileSymbols(filePath: String) {
        val idsToDelete = mutableListOf<Pair<String, String>>() // (id, name)
        val iterator = db.newIterator()
        iterator.seekToFirst()
        while (iterator.isValid()) {
            val k = String(iterator.key(), Charsets.UTF_8)
            if (k.startsWith("sym:")) {
                val sym = jsonToSymbol(String(iterator.value(), Charsets.UTF_8))
                if (sym != null && sym.filePath == filePath) {
                    idsToDelete.add(sym.id to sym.name)
                }
            }
            iterator.next()
        }
        iterator.close()

        for ((id, name) in idsToDelete) {
            db.delete(key("sym", id))
            val nameKey = key("name", name)
            val existing = db.get(nameKey)
            if (existing != null) {
                val remaining = String(existing, Charsets.UTF_8).split(",").filter { it != id }
                if (remaining.isEmpty()) db.delete(nameKey)
                else db.put(nameKey, remaining.joinToString(",").toByteArray(Charsets.UTF_8))
            }
        }
        logger.debug("Removed ${idsToDelete.size} symbols for $filePath")
    }

    /** Stores a freshness stamp for a file (mtime+size) to skip re-indexing. */
    fun setFileStamp(filePath: String, stamp: String) {
        db.put(key("file", filePath), stamp.toByteArray(Charsets.UTF_8))
    }

    fun getFileStamp(filePath: String): String? {
        val v = db.get(key("file", filePath)) ?: return null
        return String(v, Charsets.UTF_8)
    }

    /** Deletes every key with the given prefix (e.g. "libsym", "libname"). */
    fun deleteByPrefix(prefix: String) {
        val iterator = db.newIterator()
        iterator.seekToFirst()
        val keys = mutableListOf<ByteArray>()
        while (iterator.isValid()) {
            if (String(iterator.key(), Charsets.UTF_8).startsWith("$prefix:")) keys.add(iterator.key().copyOf())
            iterator.next()
        }
        iterator.close()
        for (k in keys) db.delete(k)
        logger.info("Deleted ${keys.size} keys with prefix $prefix")
    }

    /** Counts keys with the given prefix. */
    fun countByPrefix(prefix: String): Int {
        val iterator = db.newIterator()
        iterator.seekToFirst()
        var count = 0
        while (iterator.isValid()) {
            if (String(iterator.key(), Charsets.UTF_8).startsWith("$prefix:")) count++
            iterator.next()
        }
        iterator.close()
        return count
    }

    fun clearLibraries() {
        deleteByPrefix("libsym")
        deleteByPrefix("libname")
    }

    // ----- Android resources (res:) -------------------------------------------

    /** Stores a resource entry under key res:{type}:{name} (JSON list). */
    fun putResource(entry: ResourceEntry) {
        val k = key("res", "${entry.type}:${entry.name}")
        val existing = db.get(k)
        val entries = if (existing != null) {
            jsonToResources(String(existing, Charsets.UTF_8)).toMutableList()
        } else {
            mutableListOf()
        }
        entries.removeAll { it.filePath == entry.filePath && it.line == entry.line }
        entries.add(entry)
        db.put(k, resourcesToJson(entries).toByteArray(Charsets.UTF_8))
    }

    /** Finds all declarations of a resource (all configurations). */
    fun findResources(type: String, name: String): List<ResourceEntry> {
        val v = db.get(key("res", "$type:$name")) ?: return emptyList()
        return jsonToResources(String(v, Charsets.UTF_8))
    }

    /** Removes every resource entry contributed by [filePath]. */
    fun removeResourcesOfFile(filePath: String) {
        val iterator = db.newIterator()
        iterator.seekToFirst()
        val updates = mutableListOf<Pair<ByteArray, List<ResourceEntry>>>()
        while (iterator.isValid()) {
            val k = String(iterator.key(), Charsets.UTF_8)
            if (k.startsWith("res:")) {
                val entries = jsonToResources(String(iterator.value(), Charsets.UTF_8))
                val remaining = entries.filter { it.filePath != filePath }
                if (remaining.size != entries.size) {
                    updates.add(iterator.key().copyOf() to remaining)
                }
            }
            iterator.next()
        }
        iterator.close()
        for ((k, remaining) in updates) {
            if (remaining.isEmpty()) db.delete(k)
            else db.put(k, resourcesToJson(remaining).toByteArray(Charsets.UTF_8))
        }
    }

    private fun resourcesToJson(entries: List<ResourceEntry>): String =
        entries.joinToString(prefix = "[", postfix = "]") { e ->
            buildString {
                append("{")
                append("\"type\":\"${escapeJson(e.type)}\",")
                append("\"name\":\"${escapeJson(e.name)}\",")
                append("\"filePath\":\"${escapeJson(e.filePath)}\",")
                append("\"line\":${e.line},")
                append("\"col\":${e.col},")
                if (e.moduleName != null) append("\"moduleName\":\"${escapeJson(e.moduleName)}\",")
                append("}")
            }
        }

    private fun jsonToResources(json: String): List<ResourceEntry> {
        val entries = mutableListOf<ResourceEntry>()
        for (match in Regex("\\{[^}]+\\}").findAll(json)) {
            val obj = match.value
            fun str(k: String): String? =
                Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(obj)?.groupValues?.get(1)
                    ?.replace("\\\\", "\\")?.replace("\\\"", "\"")
            fun int(k: String): Int =
                Regex("\"$k\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val type = str("type") ?: continue
            val name = str("name") ?: continue
            val filePath = str("filePath") ?: continue
            entries += ResourceEntry(type, name, filePath, int("line"), int("col"), str("moduleName"))
        }
        return entries
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
     * Stores a library symbol (from an external jar/aar) in the index.
     */
    fun putLibrary(symbol: LibrarySymbol) {
        val json = libraryToJson(symbol)
        db.put(key("libsym", symbol.id), json.toByteArray(Charsets.UTF_8))

        // Update name index for libraries (case-sensitive simple name lookup).
        val nameKey = key("libname", symbol.name)
        val existing = db.get(nameKey)
        val ids = if (existing != null) {
            String(existing, Charsets.UTF_8).split(",").toMutableSet()
        } else {
            mutableSetOf()
        }
        ids.add(symbol.id)
        db.put(nameKey, ids.joinToString(",").toByteArray(Charsets.UTF_8))

        logger.debug("Indexed library symbol: ${symbol.id}")
    }

    /**
     * Finds library symbols by exact simple name.
     */
    fun findLibraryByName(name: String): List<LibrarySymbol> {
        val existing = db.get(key("libname", name)) ?: return emptyList()
        return String(existing, Charsets.UTF_8)
            .split(",")
            .mapNotNull { id -> getLibrary(id) }
    }

    fun getLibrary(symbolId: String): LibrarySymbol? {
        val json = db.get(key("libsym", symbolId)) ?: return null
        return libraryFromJson(String(json, Charsets.UTF_8))
    }

    /**
     * Finds library symbols by partial simple name (prefix/contains).
     */
    fun findLibraryByPartialName(query: String, maxResults: Int = 30): List<LibrarySymbol> {
        val results = mutableListOf<LibrarySymbol>()
        val queryLower = query.lowercase()
        val iterator = db.newIterator()
        iterator.seekToFirst()
        while (iterator.isValid() && results.size < maxResults) {
            val dbKey = String(iterator.key(), Charsets.UTF_8)
            if (dbKey.startsWith("libname:")) {
                val name = dbKey.removePrefix("libname:")
                if (name.lowercase().contains(queryLower)) {
                    val ids = String(iterator.value(), Charsets.UTF_8).split(",")
                    val symbols = ids.mapNotNull { id -> getLibrary(id) }
                    results.addAll(symbols.take(maxResults - results.size))
                }
            }
            iterator.next()
        }
        iterator.close()
        return results
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
            if (symbol.signature != null) append("\"signature\":\"${escapeJson(symbol.signature)}\",")
            if (symbol.doc != null) append("\"doc\":\"${escapeJson(symbol.doc)}\",")
            if (symbol.parameters.isNotEmpty()) append("\"params\":\"${escapeJson(encodeParams(symbol.parameters))}\",")
            if (symbol.returnType != null) append("\"returnType\":\"${escapeJson(symbol.returnType)}\",")
            if (symbol.superTypes.isNotEmpty()) append("\"superTypes\":\"${escapeJson(symbol.superTypes.joinToString(""))}\",")
            if (symbol.packageName != null) append("\"packageName\":\"${escapeJson(symbol.packageName)}\",")
            if (symbol.visibility != null) append("\"visibility\":\"${escapeJson(symbol.visibility)}\",")
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
        val signature = extractString("signature")
        val doc = extractString("doc")
        val params = extractString("params")?.let { decodeParams(it) } ?: emptyList()
        val returnType = extractString("returnType")
        val superTypes = extractString("superTypes")?.split("")?.filter { it.isNotBlank() } ?: emptyList()
        val packageName = extractString("packageName")
        val visibility = extractString("visibility")

        val kind = SymbolKind.values().find { it.value == kindValue } ?: SymbolKind.Variable

        return SymbolInfo(
            id, name, kind, filePath, line, col, containerName, moduleName,
            signature, doc, params, returnType, superTypes, packageName, visibility,
        )
    }

    // Params are encoded as name,type,default separated by  /  (control
    // chars that never appear in source code), avoiding a nested JSON parser.
    private fun encodeParams(params: List<com.androidstudioexperience.lsp.analysis.KotlinParam>): String =
        params.joinToString("") { p ->
            listOf(p.name, p.type ?: "", p.default ?: "").joinToString("")
        }

    private fun decodeParams(encoded: String): List<com.androidstudioexperience.lsp.analysis.KotlinParam> =
        encoded.split("").filter { it.isNotEmpty() }.map { part ->
            val fields = part.split("")
            com.androidstudioexperience.lsp.analysis.KotlinParam(
                name = fields.getOrElse(0) { "" },
                type = fields.getOrNull(1)?.ifBlank { null },
                default = fields.getOrNull(2)?.ifBlank { null },
            )
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

    private fun libraryToJson(symbol: LibrarySymbol): String {
        return buildString {
            append("{")
            append("\"id\":\"${escapeJson(symbol.id)}\",")
            append("\"name\":\"${escapeJson(symbol.name)}\",")
            append("\"kind\":${symbol.kind.value},")
            append("\"className\":\"${escapeJson(symbol.className)}\",")
            if (symbol.memberName != null) append("\"memberName\":\"${escapeJson(symbol.memberName)}\",")
            if (symbol.memberDescriptor != null) append("\"memberDescriptor\":\"${escapeJson(symbol.memberDescriptor)}\",")
            append("\"jarPath\":\"${escapeJson(symbol.jarPath)}\",")
            if (symbol.artifact != null) append("\"artifact\":\"${escapeJson(symbol.artifact)}\"")
            append("}")
        }
    }

    private fun libraryFromJson(json: String): LibrarySymbol? {
        fun extractString(k: String): String? {
            val pattern = "\"$k\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            val raw = pattern.find(json)?.groupValues?.get(1) ?: return null
            return raw.replace("\\\\", "\\").replace("\\\"", "\"")
        }
        fun extractInt(k: String): Int {
            val pattern = "\"$k\"\\s*:\\s*(\\d+)".toRegex()
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        val id = extractString("id") ?: return null
        val name = extractString("name") ?: return null
        val kindValue = extractInt("kind")
        val className = extractString("className") ?: return null
        val memberName = extractString("memberName")
        val memberDescriptor = extractString("memberDescriptor")
        val jarPath = extractString("jarPath") ?: return null
        val artifact = extractString("artifact")

        val kind = SymbolKind.values().find { it.value == kindValue } ?: SymbolKind.Class
        return LibrarySymbol(id, name, kind, className, memberName, memberDescriptor, jarPath, artifact)
    }
}