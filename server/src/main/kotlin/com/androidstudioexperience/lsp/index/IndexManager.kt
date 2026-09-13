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
    private val indexer: ProjectIndexer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Cache for quick symbol lookup by file
    private val fileSymbols = ConcurrentHashMap<String, List<SymbolInfo>>()

    companion object {
        private val logger = LoggerFactory.getLogger(IndexManager::class.java)

        /**
         * Creates an IndexManager with index stored in the given workspace.
         * Returns null if RocksDB cannot be initialized.
         */
        fun create(workspaceRoot: String): IndexManager? {
            val indexDir = File(workspaceRoot, ".ase-index")
            logger.info("Index directory: ${indexDir.absolutePath}")

            val index = RocksDbIndex.openOrCreate(indexDir.absolutePath)
                ?: return null

            val analyzer = KotlinAnalyzer()
            val indexer = ProjectIndexer(index, analyzer)

            return IndexManager(index, indexer)
        }
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
        val symbols = fileSymbols.remove(filePath) ?: return
        for (symbol in symbols) {
            // TODO: Remove from RocksDB (would need delete support)
        }
        logger.debug("Removed ${symbols.size} symbols for deleted file: $filePath")
    }

    /**
     * Finds a symbol definition by name and file context.
     * Uses the file's module to prefer same-module symbols.
     */
    fun findDefinition(name: String, sourceFile: String?): SymbolInfo? {
        val candidates = index.findByName(name)
        if (candidates.isEmpty()) return null

        if (candidates.size == 1) return candidates[0]

        // Prefer same-module symbols
        if (sourceFile != null) {
            val sourceModule = fileSymbols[sourceFile]?.firstOrNull()?.moduleName
            if (sourceModule != null) {
                val sameModule = candidates.find { it.moduleName == sourceModule }
                if (sameModule != null) return sameModule
            }
        }

        // Fallback: prefer non-test sources
        return candidates.find {
            val norm = it.filePath.replace('\\', '/')
            !norm.contains("/test/") && !norm.contains("/Test/")
        } ?: candidates.first()
    }

    /**
     * Finds all references (usages) of a symbol.
     */
    fun findReferences(symbol: SymbolInfo): List<SymbolReference> {
        return indexer.findReferences(symbol)
    }

    /**
     * Searches for symbols by partial name (workspace/symbol).
     */
    fun searchSymbols(query: String, maxResults: Int = 50): List<SymbolInfo> {
        return index.findByPartialName(query, maxResults)
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
