package com.androidstudioexperience.lsp.index

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import com.androidstudioexperience.lsp.analysis.KotlinAnalyzer

/**
 * Indexes Kotlin source files from a Gradle project.
 *
 * Discovers modules by reading settings.gradle(.kts) and scanning for source directories.
 * Extracts symbols using KotlinAnalyzer and stores them in RocksDbIndex.
 */
class ProjectIndexer(
    private val index: RocksDbIndex,
    private val analyzer: KotlinAnalyzer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Indexes all Kotlin source files in the project.
     * Returns the number of symbols indexed.
     */
    fun indexProject(projectRoot: String): Int {
        val root = File(projectRoot)
        if (!root.isDirectory) {
            logger.warn("Project root is not a directory: $projectRoot")
            return 0
        }

        // Find all modules by scanning for build.gradle files
        val modules = findModules(root)
        logger.info("Found ${modules.size} modules: ${modules.map { it.name }}")

        var totalSymbols = 0

        for (module in modules) {
            val moduleName = module.name
            logger.info("Indexing module: $moduleName")

            // Find all Kotlin source directories
            val sourceDirs = findSourceDirectories(module)
            logger.debug("  Source dirs: ${sourceDirs.map { it.absolutePath }}")

            for (sourceDir in sourceDirs) {
                val ktFiles = findKotlinFiles(sourceDir)
                logger.debug("  Found ${ktFiles.size} Kotlin files in ${sourceDir.name}")

                for (file in ktFiles) {
                    val symbols = indexFile(file, moduleName)
                    totalSymbols += symbols.size
                }
            }
        }

        // Store metadata
        index.setMeta("indexedAt", System.currentTimeMillis().toString())
        index.setMeta("projectRoot", projectRoot)
        index.setMeta("symbolCount", totalSymbols.toString())

        logger.info("Indexed $totalSymbols symbols from ${modules.size} modules")
        return totalSymbols
    }

    /**
     * Indexes a single Kotlin file.
     * Returns the list of symbols extracted.
     */
    fun indexFile(file: File, moduleName: String? = null): List<SymbolInfo> {
        val content = try {
            file.readText()
        } catch (e: Exception) {
            logger.warn("Failed to read file ${file.absolutePath}: ${e.message}")
            return emptyList()
        }

        val filePath = file.absolutePath
        val docSymbols = analyzer.documentSymbols(content)
        val symbols = extractSymbols(docSymbols, filePath, moduleName)

        // Store symbols in index
        for (symbol in symbols) {
            index.putSymbol(symbol)
        }

        logger.debug("Indexed ${symbols.size} symbols from ${file.name}")
        return symbols
    }

    /**
     * Extracts SymbolInfo from LSP DocumentSymbol list.
     */
    private fun extractSymbols(
        docSymbols: List<DocumentSymbol>,
        filePath: String,
        moduleName: String?,
        container: String? = null
    ): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()

        for (sym in docSymbols) {
            val symbol = SymbolInfo(
                id = SymbolInfo.makeId(filePath, sym.range.start.line, sym.name),
                name = sym.name,
                kind = sym.kind,
                filePath = filePath,
                line = sym.range.start.line,
                col = sym.selectionRange.start.character,
                containerName = container,
                moduleName = moduleName
            )
            result.add(symbol)

            // Recurse into children
            if (sym.children.isNotEmpty()) {
                val childSymbols = extractSymbols(sym.children, filePath, moduleName, container = sym.name)
                result.addAll(childSymbols)
            }
        }

        return result
    }

    /**
     * Finds all modules in the project by scanning for build.gradle files.
     */
    private fun findModules(root: File): List<File> {
        val modules = mutableListOf<File>()
        
        // Root is always a module
        if (hasBuildGradle(root)) {
            modules.add(root)
        }

        // Scan for subdirectories with build.gradle
        root.walk()
            .filter { it.isDirectory && it != root && hasBuildGradle(it) }
            .forEach { modules.add(it) }

        return modules
    }

    private fun hasBuildGradle(dir: File): Boolean {
        return File(dir, "build.gradle").exists() ||
               File(dir, "build.gradle.kts").exists()
    }

    /**
     * Finds source directories within a module.
     */
    private fun findSourceDirectories(module: File): List<File> {
        val sourceDirs = mutableListOf<File>()

        // Standard Gradle source sets
        val commonPaths = listOf(
            "src/commonMain/kotlin",
            "src/commonTest/kotlin",
            "src/jvmMain/kotlin",
            "src/jvmTest/kotlin",
            "src/androidMain/kotlin",
            "src/androidUnitTest/kotlin",
            "src/iosMain/kotlin",
            "src/iosTest/kotlin",
            "src/main/kotlin",
            "src/test/kotlin",
            "src/main/java",
            "src/test/java"
        )

        for (path in commonPaths) {
            val dir = File(module, path)
            if (dir.isDirectory) {
                sourceDirs.add(dir)
            }
        }

        // Also check for src/*.kt files at module root
        if (File(module, "src").isDirectory) {
            File(module, "src").walk()
                .filter { it.isDirectory && it.name == "kotlin" }
                .forEach { sourceDirs.add(it) }
        }

        return sourceDirs.distinct()
    }

    /**
     * Finds all Kotlin files in a directory tree.
     */
    private fun findKotlinFiles(dir: File): List<File> {
        return dir.walk()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            // Exclude build output regardless of platform separator
            .filter { !it.absolutePath.replace('\\', '/').contains("/build/") }
            .toList()
    }

    /**
     * Finds all references to a symbol in the indexed files.
     * This is a simple text-based search for the symbol name.
     */
    fun findReferences(symbol: SymbolInfo): List<SymbolReference> {
        val references = mutableListOf<SymbolReference>()
        
        // Get all indexed file paths
        val allSymbols = index.findByPartialName("", maxResults = 10000)
        val fileSet = allSymbols.map { it.filePath }.toSet()

        for (filePath in fileSet) {
            val file = File(filePath)
            if (!file.exists()) continue

            val lines = file.readLines()
            for ((lineIdx, line) in lines.withIndex()) {
                // Simple text search for symbol name
                // TODO: Use proper Kotlin parser to avoid false positives
                val pattern = "\\b${Regex.escape(symbol.name)}\\b".toRegex()
                for (match in pattern.findAll(line)) {
                    // Skip the declaration itself
                    if (lineIdx == symbol.line && match.range.first == symbol.col) continue

                    references.add(
                        SymbolReference(
                            symbolId = symbol.id,
                            filePath = filePath,
                            line = lineIdx,
                            col = match.range.first,
                            referenceText = match.value
                        )
                    )
                }
            }
        }

        return references
    }

    /**
     * Re-indexes the entire project (clears and rebuilds the index).
     */
    fun reindex(projectRoot: String): Int {
        logger.info("Re-indexing project: $projectRoot")
        index.clear()
        return indexProject(projectRoot)
    }
}
