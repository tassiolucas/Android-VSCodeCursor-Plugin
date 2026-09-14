package com.androidstudioexperience.lsp.index

import com.androidstudioexperience.lsp.analysis.KotlinAnalyzer
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the project symbol index.
 * Handles initialization, incremental updates, and query operations.
 */
class IndexManager private constructor(
    private val index: RocksDbIndex,
    private val indexer: ProjectIndexer,
    private val indexDir: File
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Cache for quick symbol lookup by file
    private val fileSymbols = ConcurrentHashMap<String, List<SymbolInfo>>()

    companion object {
        private val logger = LoggerFactory.getLogger(IndexManager::class.java)

        private val CLASS_LIKE_KINDS = setOf(
            org.eclipse.lsp4j.SymbolKind.Class,
            org.eclipse.lsp4j.SymbolKind.Interface,
            org.eclipse.lsp4j.SymbolKind.Object,
            org.eclipse.lsp4j.SymbolKind.Enum,
        )

        /**
         * Creates an IndexManager with index stored in the given workspace.
         * Returns null if RocksDB cannot be initialized.
         */
        fun create(workspaceRoot: String, includeGeneratedSources: Boolean = true): IndexManager? {
            val indexDir = File(workspaceRoot, ".ase-index")
            logger.info("Index directory: ${indexDir.absolutePath}")

            val index = RocksDbIndex.openOrCreate(indexDir.absolutePath)
                ?: return null

            // Schema migration: a version mismatch drops the (stale) contents;
            // the project is re-indexed from source right after.
            if (index.getMeta("schemaVersion") != SymbolInfo.SCHEMA_VERSION) {
                logger.info("Index schema changed -> rebuilding index from scratch")
                index.clear()
                index.setMeta("schemaVersion", SymbolInfo.SCHEMA_VERSION)
            }

            val analyzer = KotlinAnalyzer()
            val indexer = ProjectIndexer(index, analyzer, includeGeneratedSources)

            return IndexManager(index, indexer, indexDir)
        }
    }

    /**
     * Resolves the project compile classpath via Gradle and indexes external
     * library symbols (jars/aars). Returns the number of library symbols.
     *
     * Persistence: if the build files' stamp is unchanged and libraries were
     * already indexed, the Gradle run and the ASM re-index are skipped (they're
     * the expensive ~40s steps on startup).
     */
    fun indexExternalLibraries(projectRoot: String): Int {
        val stamp = buildFilesStamp(projectRoot)
        val cachedStamp = index.getMeta("classpathStamp")
        val cachedCount = index.getMeta("libCount")?.toIntOrNull()
        if (cachedStamp == stamp && cachedCount != null && cachedCount > 0) {
            logger.info("Reusing cached library index ($cachedCount symbols, classpath unchanged)")
            return cachedCount
        }

        val jars = ClasspathResolver(indexDir).resolve(projectRoot)
        if (jars.isEmpty()) {
            logger.warn("No classpath resolved — external library indexing skipped")
            return cachedCount ?: 0
        }

        index.clearLibraries()
        val symbols = LibraryIndexer().indexJars(jars)
        for (symbol in symbols) {
            index.putLibrary(symbol)
        }
        index.setMeta("classpathStamp", stamp)
        index.setMeta("libCount", symbols.size.toString())
        logger.info("Indexed ${symbols.size} external library symbols from ${jars.size} jars/aars")
        return symbols.size
    }

    /**
     * A freshness stamp over the project's build files (settings + module
     * build scripts + version catalog) so classpath changes trigger re-index.
     */
    private fun buildFilesStamp(projectRoot: String): String {
        val buildFileNames = setOf(
            "settings.gradle", "settings.gradle.kts",
            "build.gradle", "build.gradle.kts",
            "libs.versions.toml",
        )
        val sb = StringBuilder(projectRoot)
        File(projectRoot).walkTopDown()
            .onEnter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "node_modules" }
            .filter { it.isFile && it.name in buildFileNames }
            .sortedBy { it.absolutePath }
            .forEach { sb.append('|').append(it.absolutePath).append('=').append(it.lastModified()) }
        return sb.toString().hashCode().toString()
    }

    /**
     * Finds an external library symbol (class/member) by simple name.
     */
    fun findLibraryDefinition(name: String): LibrarySymbol? {
        val candidates = index.findLibraryByName(name)
        if (candidates.isEmpty()) return null
        // Prefer the class declaration over members with the same simple name.
        return candidates.firstOrNull { it.memberName == null } ?: candidates.first()
    }

    /**
     * All library members (methods/fields/constructors) with this simple name
     * — used by signature help to list library callables.
     */
    fun findLibraryMembers(name: String): List<LibrarySymbol> =
        index.findLibraryByName(name).filter { it.memberName != null }

    /**
     * Searches library symbols by partial simple name (workspace/symbol).
     */
    fun searchLibraries(query: String, maxResults: Int = 30): List<LibrarySymbol> {
        return index.findLibraryByPartialName(query, maxResults)
    }

    /**
     * Indexes the entire project. Should be called once on workspace open.
     * Returns the number of symbols indexed.
     */
    fun indexProject(projectRoot: String): Int {
        val count = indexer.indexProject(projectRoot)
        refreshFileCache()
        return count
    }

    /**
     * Re-indexes the entire project (clears existing index).
     */
    fun reindex(projectRoot: String): Int {
        val count = indexer.reindex(projectRoot)
        refreshFileCache()
        return count
    }

    /**
     * Indexes a single file (incremental update).
     */
    fun indexFile(filePath: String, content: String, moduleName: String? = null): List<SymbolInfo> {
        val file = File(filePath)
        val symbols = indexer.indexFile(file, moduleName)
        fileSymbols[filePath] = symbols
        return symbols
    }

    /**
     * Removes a file from the index (when deleted).
     */
    fun removeFile(filePath: String) {
        val symbols = fileSymbols.remove(filePath)
        index.removeFileSymbols(filePath)
        // Clear the freshness stamp so a recreated file is re-indexed.
        index.setFileStamp(filePath, "")
        logger.debug("Removed ${symbols?.size ?: 0} symbols for deleted file: $filePath")
    }

    /**
     * Finds a symbol definition by name and file context.
     * Uses the file's module to prefer same-module symbols.
     */
    fun findDefinition(name: String, sourceFile: String?): SymbolInfo? =
        findAllDefinitions(name, sourceFile).firstOrNull()

    /**
     * All known declarations with this simple name, best first:
     * same-module symbols, then non-test sources, then the rest.
     * Used for multi-candidate go-to-definition and overload listing.
     */
    fun findAllDefinitions(name: String, sourceFile: String?): List<SymbolInfo> {
        val candidates = index.findByName(name)
        if (candidates.size <= 1) return candidates

        val sourceModule = sourceFile?.let { fileSymbols[it]?.firstOrNull()?.moduleName }
        return candidates.sortedWith(
            compareBy(
                { if (sourceModule != null && it.moduleName != sourceModule) 1 else 0 },
                {
                    val norm = it.filePath.replace('\\', '/')
                    if (norm.contains("/test/") || norm.contains("/Test/")) 1 else 0
                },
            )
        )
    }

    /**
     * Finds class-like symbols that declare [name] as a direct super type
     * (structural "go to implementations").
     */
    fun findImplementations(name: String): List<SymbolInfo> =
        index.findAllSymbols().filter { sym ->
            sym.kind in CLASS_LIKE_KINDS && name in sym.superTypes
        }

    /**
     * Direct super types of [symbol] resolved against the index
     * (structural type hierarchy).
     */
    fun findSuperTypes(symbol: SymbolInfo): List<SymbolInfo> =
        symbol.superTypes.mapNotNull { findDefinition(it, symbol.filePath) }

    /**
     * Finds all references (usages) of a symbol.
     */
    fun findReferences(symbol: SymbolInfo): List<SymbolReference> {
        return indexer.findReferences(symbol)
    }

    /**
     * Stored (index-time) references of a symbol — cheap, for CodeLens counts.
     */
    fun getStoredReferences(symbol: SymbolInfo): List<SymbolReference> =
        index.getReferences(symbol.id)

    /**
     * Searches for symbols by partial name (workspace/symbol).
     */
    fun searchSymbols(query: String, maxResults: Int = 50): List<SymbolInfo> {
        return index.findByPartialName(query, maxResults)
    }

    /**
     * CamelCase symbol search ("LiSc" -> "LoginScreen") — merged into
     * workspace/symbol results when the query contains uppercase letters.
     */
    fun searchSymbolsCamel(query: String, maxResults: Int = 50): List<SymbolInfo> {
        return index.findByCamelCase(query, maxResults)
    }

    /**
     * Indexes a single file, auto-detecting its Gradle module by walking up
     * to the nearest build.gradle(.kts). Used by file-watcher events.
     */
    fun indexFileWithDetectedModule(filePath: String): List<SymbolInfo> {
        val file = File(filePath)
        if (!file.isFile) return emptyList()
        val module = detectModuleDir(file.parentFile)
        return indexFile(filePath, file.readText(), module?.name)
    }

    /** Re-indexes one Android resource XML file (file-watcher path). */
    fun indexResourceFile(filePath: String) {
        val file = File(filePath)
        if (!file.isFile) return
        indexer.indexResourceFile(file, detectModuleDir(file.parentFile)?.name)
    }

    /** Removes every resource contributed by a deleted XML file. */
    fun removeResourceFile(filePath: String) {
        index.removeResourcesOfFile(filePath)
    }

    /** Finds all declarations of an Android resource (all configurations). */
    fun findResources(type: String, name: String): List<ResourceEntry> =
        index.findResources(type, name)

    private fun detectModuleDir(dir: File?): File? {
        var current = dir
        while (current != null) {
            if (File(current, "build.gradle").exists() || File(current, "build.gradle.kts").exists()) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    /**
     * Gets all symbols in a file.
     */
    fun getSymbolsInFile(filePath: String): List<SymbolInfo> {
        return fileSymbols[filePath] ?: emptyList()
    }

    /**
     * Checks if the project has been indexed.
     */
    fun isIndexed(): Boolean {
        return index.getMeta("indexedAt") != null
    }

    /**
     * Gets the last index timestamp.
     */
    fun getLastIndexedAt(): Long {
        return index.getMeta("indexedAt")?.toLongOrNull() ?: 0
    }

    /**
     * Gets the total number of indexed symbols.
     */
    fun getSymbolCount(): Int {
        return index.getMeta("symbolCount")?.toIntOrNull() ?: 0
    }

    /**
     * Closes the index manager and releases resources.
     */
    fun close() {
        index.close()
        logger.info("Index manager closed")
    }

    private fun refreshFileCache() {
        // Rebuild file cache from index
        fileSymbols.clear()
        val allSymbols = index.findByPartialName("", maxResults = 100000)
        for (symbol in allSymbols) {
            val list = fileSymbols.getOrPut(symbol.filePath) { mutableListOf() }
            (list as MutableList).add(symbol)
        }
        logger.debug("Refreshed file cache: ${fileSymbols.size} files")
    }
}
