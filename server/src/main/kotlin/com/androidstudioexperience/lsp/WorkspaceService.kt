package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.documents.DocumentManager
import com.androidstudioexperience.lsp.index.IndexManager
import com.androidstudioexperience.lsp.index.LibraryDecompiler
import com.androidstudioexperience.lsp.index.SymbolInfo
import com.androidstudioexperience.lsp.util.Uris
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Workspace-level LSP handlers. Uses the RocksDB-backed project index
 * for cross-module symbol search.
 */
class WorkspaceService(
    private val documents: DocumentManager,
    private val indexProvider: () -> IndexManager? = { null }
) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val decompiler = LibraryDecompiler()

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> =
        CompletableFutures.computeAsync {
            val query = params.query
            val indexManager = indexProvider()
            logger.info("workspace/symbol query: '$query', indexManager null? ${indexManager == null}")
            
            // Prefer index results (cross-file)
            val indexResults = if (indexManager != null) {
                val symbols = indexManager.searchSymbols(query, maxResults = 50).toMutableList()
                // CamelCase fallback: "LiSc" -> "LoginScreen" (Android Studio style).
                if (query.any { it.isUpperCase() }) {
                    val existing = symbols.map { it.id }.toHashSet()
                    val camel = indexManager.searchSymbolsCamel(query, maxResults = 30)
                        .filter { it.id !in existing }
                    symbols.addAll(camel)
                }
                logger.info("Index returned ${symbols.size} results")
                symbols.map { sym ->
                    val loc = sym.toLocation()
                    loc.uri = SymbolInfo.filePathToUri(sym.filePath)
                    SymbolInformation(sym.name, sym.kind, loc, sym.detailLabel())
                }
            } else {
                logger.info("Fallback to open documents (indexManager is null)")
                // Fallback to open documents only
                documents.getAll()
                    .flatMap { (uri, text) -> analyzeSymbols(uri, text, query) }
                    .distinctBy { it.name + it.location.uri + it.location.range.start.line }
            }

            // Merge external library symbols (Phase 3).
            val libResults = if (indexManager != null) {
                indexManager.searchLibraries(query, maxResults = 20).map { lib ->
                    SymbolInformation(lib.name, lib.kind, lib.toLocation(), lib.artifact)
                }
            } else {
                emptyList()
            }

            Either.forLeft((indexResults + libResults).toMutableList())
        }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> =
        CompletableFuture.supplyAsync {
            when (params.command) {
                "ase.decompile" -> {
                    val arg = params.arguments?.firstOrNull()
                    val jarPath = extractArg(arg, "jarPath")
                    val className = extractArg(arg, "className")
                    if (jarPath == null || className == null) {
                        logger.warn("ase.decompile: missing jarPath/className (got ${arg?.javaClass})")
                        null
                    } else {
                        logger.info("ase.decompile: $className from $jarPath")
                        decompiler.decompileClass(jarPath, className)
                    }
                }
                else -> null
            }
        }

    /** Reads a field from a command argument that may be a Gson Map or JsonObject. */
    private fun extractArg(arg: Any?, key: String): String? = when (arg) {
        is Map<*, *> -> arg[key] as? String
        is com.google.gson.JsonObject -> {
            val el = arg.get(key)
            if (el != null && el.isJsonPrimitive) el.asString else null
        }
        is com.google.gson.JsonElement -> {
            if (arg.isJsonObject) extractArg(arg.asJsonObject, key) else null
        }
        else -> null
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        // Incremental indexing for files created/changed/deleted outside the editor.
        for (change in params.changes) {
            val path = uriToPath(change.uri) ?: continue
            when (change.type) {
                org.eclipse.lsp4j.FileChangeType.Created,
                org.eclipse.lsp4j.FileChangeType.Changed -> {
                    val file = java.io.File(path)
                    val ext = file.extension
                    if (!file.isFile) continue
                    if (ext == "kt" || ext == "kts" || ext == "java") {
                        indexProvider()?.indexFileWithDetectedModule(path)
                    } else if (ext == "xml" && path.replace('\\', '/').contains("/res/")) {
                        indexProvider()?.indexResourceFile(path)
                    }
                }
                org.eclipse.lsp4j.FileChangeType.Deleted -> {
                    if (path.endsWith(".xml") && path.replace('\\', '/').contains("/res/")) {
                        indexProvider()?.removeResourceFile(path)
                    } else {
                        indexProvider()?.removeFile(path)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun uriToPath(uri: String): String? = Uris.uriToPath(uri)
}

private fun analyzeSymbols(uri: String, text: String, query: String): List<SymbolInformation> {
    val model = com.androidstudioexperience.lsp.analysis.KotlinAnalyzer().documentSymbols(text)
    return model
        .filter { it.name.lowercase().contains(query.lowercase()) }
        .map { ds ->
            val rng = ds.range
                ?: org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 0))
            SymbolInformation(ds.name, ds.kind, org.eclipse.lsp4j.Location(uri, rng), null)
        }
}