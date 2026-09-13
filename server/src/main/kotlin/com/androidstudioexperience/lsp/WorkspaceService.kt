package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.documents.DocumentManager
import com.androidstudioexperience.lsp.index.IndexManager
import com.androidstudioexperience.lsp.index.SymbolInfo
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

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> =
        CompletableFutures.computeAsync {
            val query = params.query
            val indexManager = indexProvider()
            logger.info("workspace/symbol query: '$query', indexManager null? ${indexManager == null}")
            
            // Prefer index results (cross-file)
            val indexResults = if (indexManager != null) {
                val symbols = indexManager.searchSymbols(query, maxResults = 50)
                logger.info("Index returned ${symbols.size} results")
                symbols.map { sym ->
                    val loc = sym.toLocation()
                    loc.uri = SymbolInfo.filePathToUri(sym.filePath)
                    SymbolInformation(sym.name, sym.kind, loc, sym.moduleName)
                }
            } else {
                logger.info("Fallback to open documents (indexManager is null)")
                // Fallback to open documents only
                documents.getAll()
                    .flatMap { (uri, text) -> analyzeSymbols(uri, text, query) }
                    .distinctBy { it.name + it.location.uri + it.location.range.start.line }
            }
            
            Either.forLeft(indexResults.toMutableList())
        }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> =
        CompletableFuture.completedFuture(null)

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        // Handle file changes for incremental indexing
        for (change in params.changes) {
            when (change.type) {
                org.eclipse.lsp4j.FileChangeType.Created,
                org.eclipse.lsp4j.FileChangeType.Changed -> {
                    // Could trigger incremental re-index here
                }
                org.eclipse.lsp4j.FileChangeType.Deleted -> {
                    indexProvider()?.removeFile(uriToPath(change.uri))
                }
            }
        }
    }

    private fun uriToPath(uri: String): String {
        return if (uri.startsWith("file:///")) {
            val decoded = try {
                java.net.URLDecoder.decode(uri.removePrefix("file:///"), "UTF-8")
            } catch (e: Exception) {
                uri.removePrefix("file:///")
            }
            if (System.getProperty("os.name").lowercase().contains("win")) {
                decoded.replace('/', '\\')
            } else {
                decoded
            }
        } else {
            uri
        }
    }
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