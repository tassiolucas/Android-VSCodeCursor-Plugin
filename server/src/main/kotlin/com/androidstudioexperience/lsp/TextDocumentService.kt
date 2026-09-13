package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.KotlinAnalyzer
import com.androidstudioexperience.lsp.documents.DocumentManager
import com.androidstudioexperience.lsp.index.IndexManager
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class TextDocumentService(
    private val documents: DocumentManager,
    private val indexProvider: () -> IndexManager? = { null }
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val analyzer = KotlinAnalyzer()

    // ----- Document lifecycle -------------------------------------------------

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        documents.open(uri, params.textDocument.text)
        analyzer.onDocumentOpened(uri, params.textDocument.text)
        
        // Index file incrementally
        val filePath = uriToPath(uri)
        if (filePath != null) {
            indexProvider()?.indexFile(filePath, params.textDocument.text)
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        for (change in params.contentChanges) {
            documents.change(uri, change.range, change.text)
        }
        documents.get(uri)?.let { analyzer.onDocumentChanged(uri, it) }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        analyzer.onDocumentClosed(uri)
        documents.close(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Re-index file on save
        val uri = params.textDocument.uri
        documents.get(uri)?.let { text ->
            val filePath = uriToPath(uri)
            if (filePath != null) {
                indexProvider()?.indexFile(filePath, text)
            }
        }
    }

    // ----- Intelligence -------------------------------------------------------

    override fun hover(params: org.eclipse.lsp4j.HoverParams): CompletableFuture<Hover> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            val content = analyzer.hover(uri, text, params.position.line, params.position.character)
            if (content.isNullOrBlank()) null
            else Hover(MarkupContent(MarkupKind.MARKDOWN, content))
        }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            
            // Try in-file definition first (Phase 1)
            val inFileLoc = analyzer.definition(uri, text, params.position.line, params.position.character)
            if (inFileLoc != null) {
                logger.debug("Definition resolved in-file")
                return@computeAsync Either.forLeft(mutableListOf(inFileLoc))
            }
            
            // Try cross-file definition via index (Phase 2)
            logger.debug("Trying cross-file definition via index")
            val filePath = uriToPath(uri)
            val symbolName = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync null
            
            val indexManager = indexProvider()
            if (indexManager != null && filePath != null) {
                val symbol = indexManager.findDefinition(symbolName, filePath)
                if (symbol != null) {
                    logger.debug("Cross-file definition found: ${symbol.name} in ${symbol.filePath}")
                    val loc = symbol.toLocation()
                    return@computeAsync Either.forLeft(mutableListOf(loc))
                }
            }
            
            null
        }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync mutableListOf()
            analyzer.documentSymbols(text)
                .map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }
                .toMutableList()
        }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> =
        CompletableFutures.computeAsync { null }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> =
        CompletableFutures.computeAsync { null }

    private fun uriToPath(uri: String): String? {
        return try {
            if (uri.startsWith("file:///")) {
                val decoded = java.net.URLDecoder.decode(uri.removePrefix("file:///"), "UTF-8")
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    decoded.replace('/', '\\')
                } else {
                    decoded
                }
            } else {
                uri
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractSymbolAtPosition(text: String, line: Int, col: Int): String? {
        val lines = text.lines()
        if (line < 0 || line >= lines.size) return null
        val lineText = lines[line]
        if (col < 0 || col >= lineText.length) return null
        if (!lineText[col].isLetterOrDigit() && lineText[col] != '_') return null

        // Expand outward to full identifier
        var start = col
        while (start > 0 && (lineText[start - 1].isLetterOrDigit() || lineText[start - 1] == '_')) start--
        var end = col
        while (end < lineText.length && (lineText[end].isLetterOrDigit() || lineText[end] == '_')) end++

        val ident = lineText.substring(start, end)
        return ident.ifBlank { null }
    }
}