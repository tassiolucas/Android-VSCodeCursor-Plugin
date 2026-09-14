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
    private val analyzer: KotlinAnalyzer,
    private val includeGeneratedSources: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val javaAnalyzer = com.androidstudioexperience.lsp.analysis.JavaAnalyzer()
    private val resourceIndexer = ResourceIndexer(index)

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
        val changedFiles = mutableListOf<File>()

        for (module in modules) {
            val moduleName = module.name
            logger.info("Indexing module: $moduleName")

            // Find all Kotlin/Java source directories
            val sourceDirs = findSourceDirectories(module)
            logger.debug("  Source dirs: ${sourceDirs.map { it.absolutePath }}")

            for (sourceDir in sourceDirs) {
                val ktFiles = findSourceFiles(sourceDir)
                logger.debug("  Found ${ktFiles.size} source files in ${sourceDir.name}")

                for (file in ktFiles) {
                    // Skip files unchanged since the previous session (persistent index).
                    if (isUnchanged(file)) {
                        continue
                    }
                    val symbols = indexFile(file, moduleName)
                    changedFiles.add(file)
                    totalSymbols += symbols.size
                }
            }

            // Android XML resources (R.string.x / @layout/y navigation).
            val resCount = resourceIndexer.indexModuleResources(module, moduleName)
            if (resCount > 0) {
                logger.debug("  Indexed $resCount resources in module $moduleName")
            }
        }

        // Second pass: references (symbols of all modules must exist first so
        // cross-file references resolve to real symbol ids).
        for (file in changedFiles) {
            index.removeRefsOfFile(file.absolutePath)
            indexReferencesInFile(file)
        }

        // Store metadata (count reflects the full index contents, not just this run)
        index.setMeta("indexedAt", System.currentTimeMillis().toString())
        index.setMeta("projectRoot", projectRoot)
        index.setMeta("symbolCount", index.countByPrefix("sym").toString())

        logger.info("Indexed $totalSymbols new/changed symbols from ${modules.size} modules")
        return index.countByPrefix("sym")
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

        // Clean previous entries for this file so re-indexing stays consistent.
        index.removeFileSymbols(filePath)

        val topLevel = when (file.extension) {
            "java" -> javaAnalyzer.topLevelSymbols(content)
            else -> analyzer.topLevelSymbols(content)
        }
        val symbols = extractSymbols(topLevel, filePath, moduleName)

        for (symbol in symbols) {
            index.putSymbol(symbol)
        }
        index.setFileStamp(filePath, stampOf(file))
        index.removeRefsOfFile(filePath)
        indexReferencesInFile(file)

        logger.debug("Indexed ${symbols.size} symbols from ${file.name}")
        return symbols
    }

    private fun stampOf(file: File): String = "${file.lastModified()}:${file.length()}"

    /** True when the file's mtime+size match what we indexed previously. */
    private fun isUnchanged(file: File): Boolean =
        index.getFileStamp(file.absolutePath) == stampOf(file)

    /**
     * Converts the analyzer's hierarchical symbols into flat [SymbolInfo]
     * records (children carry containerName/qualifiedName already).
     */
    private fun extractSymbols(
        symbols: List<com.androidstudioexperience.lsp.analysis.Symbol>,
        filePath: String,
        moduleName: String?,
    ): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        for (sym in symbols) {
            result.add(
                SymbolInfo(
                    id = SymbolInfo.makeId(filePath, sym.range.start.line, sym.qualifiedName),
                    name = sym.name,
                    kind = sym.kind,
                    filePath = filePath,
                    line = sym.range.start.line,
                    col = sym.nameRange.start.character,
                    containerName = sym.containerName,
                    moduleName = moduleName,
                    signature = sym.signature.ifBlank { null },
                    doc = sym.doc,
                    parameters = sym.parameters,
                    returnType = sym.returnType,
                    superTypes = sym.superTypes,
                    packageName = sym.packageName,
                    visibility = sym.visibility,
                )
            )
            if (sym.children.isNotEmpty()) {
                result.addAll(extractSymbols(sym.children, filePath, moduleName))
            }
        }
        return result
    }

    /** Re-indexes a single Android resource XML file. */
    fun indexResourceFile(file: File, moduleName: String?) =
        resourceIndexer.indexResourceFile(file, moduleName)

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

        // KSP/KAPT/etc. generated sources (setting: ase.indexing.generatedSources).
        if (includeGeneratedSources) {
            val gen = File(module, "build/generated")
            if (gen.isDirectory) {
                gen.walkTopDown()
                    .filter { it.isDirectory && (it.name == "kotlin" || it.name == "java") }
                    .forEach { sourceDirs.add(it) }
            }
        }

        return sourceDirs.distinct()
    }

    /**
     * Finds all Kotlin/Java source files in a directory tree.
     * Build output is excluded, except build/generated when the
     * generated-sources setting is enabled.
     */
    private fun findSourceFiles(dir: File): List<File> {
        return dir.walk()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts" || it.extension == "java") }
            .filter { file ->
                val p = file.absolutePath.replace('\\', '/')
                !p.contains("/build/") || (includeGeneratedSources && p.contains("/build/generated/"))
            }
            .toList()
    }

    /**
     * Finds all references to a symbol in the indexed files.
     * Lexer-aware: comments and string literals are masked out before the
     * word-boundary match (no hits inside `"${symbol.name}"` or comments).
     * When the name is ambiguous (declared in multiple places), files are
     * pre-filtered by package/import relation to the symbol's package.
     */
    fun findReferences(symbol: SymbolInfo): List<SymbolReference> {
        val references = mutableListOf<SymbolReference>()

        // Get all indexed file paths
        val allSymbols = index.findByPartialName("", maxResults = 10000)
        val fileSet = allSymbols.map { it.filePath }.toSet()

        val ambiguous = index.findByName(symbol.name).size > 1
        val pattern = "\\b${Regex.escape(symbol.name)}\\b".toRegex()

        for (filePath in fileSet) {
            val file = File(filePath)
            if (!file.exists()) continue

            val text = file.readText()
            val maskedLines = com.androidstudioexperience.lsp.analysis.KotlinMask.maskLines(text)

            // Ambiguity filter: only keep files that plausibly see THIS symbol
            // (same package, explicit import, or fully-qualified usage).
            if (ambiguous && symbol.packageName != null) {
                val head = maskedLines.take(80)
                val filePkg = head.firstNotNullOfOrNull {
                    Regex("""^\s*package\s+([\w.]+)""").find(it)?.groupValues?.get(1)
                }
                val fqn = "${symbol.packageName}.${symbol.name}"
                val imported = head.any { line ->
                    val imp = Regex("""^\s*import\s+([\w.]+)""").find(line)?.groupValues?.get(1)
                    imp == fqn || imp == "${symbol.packageName}.*"
                }
                val fqnUsed = text.contains(fqn)
                if (filePkg != symbol.packageName && !imported && !fqnUsed) continue
            }

            for ((lineIdx, line) in maskedLines.withIndex()) {
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
     * Collects identifier occurrences of indexed symbols within [file] and
     * stores them as persistent references (prefix ref:). Called for
     * new/changed files so CodeLens "N usages" stays cheap.
     */
    fun indexReferencesInFile(file: File) {
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return
        }
        val maskedLines = com.androidstudioexperience.lsp.analysis.KotlinMask.maskLines(text)
        val word = Regex("""\b[A-Za-z_]\w*\b""")
        val byName = mutableMapOf<String, MutableList<Pair<Int, Int>>>()

        for ((lineIdx, line) in maskedLines.withIndex()) {
            for (m in word.findAll(line)) {
                val name = m.value
                if (name.length < 2 || name[0].isLowerCase() && !name.contains('_') && name.length < 4) {
                    // cheap noise filter for local throwaway names
                }
                byName.getOrPut(name) { mutableListOf() }.add(lineIdx to m.range.first)
            }
        }

        // Drop stale refs this file contributed previously, then re-add.
        for ((name, positions) in byName) {
            val symbols = index.findByName(name)
            if (symbols.isEmpty()) continue
            for (sym in symbols) {
                val refs = positions.map { (line, col) ->
                    SymbolReference(sym.id, file.absolutePath, line, col, name)
                }
                index.replaceFileReferences(sym.id, file.absolutePath, refs)
            }
        }
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
