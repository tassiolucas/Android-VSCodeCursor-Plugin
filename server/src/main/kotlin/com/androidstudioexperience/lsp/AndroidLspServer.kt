package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.documents.DocumentManager
import com.androidstudioexperience.lsp.index.IndexManager
import org.eclipse.lsp4j.DefinitionOptions
import org.eclipse.lsp4j.DocumentSymbolOptions
import org.eclipse.lsp4j.HoverOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Root LSP server. Owns the text document store and exposes the
 * language/workspace services to LSP4J.
 */
class AndroidLspServer : LanguageServer, LanguageClientAware {

    private val logger = LoggerFactory.getLogger(javaClass)
    private var client: LanguageClient? = null
    private val documents = DocumentManager()
    private var indexManager: IndexManager? = null
    // Services capture the CURRENT indexManager via a provider lambda, so
    // they see it once initialize() assigns it. Created up-front because
    // LSP4J caches service references before initialize() completes.
    private val textDocumentService: TextDocumentService = TextDocumentService(documents) { indexManager }
    private val workspaceService: WorkspaceService = WorkspaceService(documents) { indexManager }
    private var rootUri: String? = null

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        rootUri = params.rootUri ?: params.workspaceFolders?.firstOrNull()?.uri
        
        // Initialize index manager if we have a workspace root
        if (rootUri != null) {
            val rootPath = uriToPath(rootUri!!)
            logger.info("Workspace root: $rootUri -> $rootPath")
            indexManager = IndexManager.create(rootPath)
            if (indexManager != null) {
                logger.info("Index manager initialized for $rootPath")
                // Start background indexing
                CompletableFuture.runAsync {
                    try {
                        val count = indexManager!!.indexProject(rootPath)
                        logger.info("Background indexing complete: $count symbols")
                        client?.let { c ->
                            c.showMessage(org.eclipse.lsp4j.MessageParams(
                                org.eclipse.lsp4j.MessageType.Info,
                                "ASE: Indexed $count symbols from project"
                            ))
                        }
                    } catch (e: Exception) {
                        logger.error("Indexing failed: ${e.message}", e)
                    }
                }
            } else {
                logger.warn("Failed to initialize index manager (RocksDB native lib issue?)")
            }
        }

        // Services are created up-front with a provider lambda, so they always
        // see the latest indexManager once this method assigns it.
        return initializeResult()
    }

    private fun initializeResult(): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            val sync = TextDocumentSyncOptions().apply {
                openClose = true
                change = TextDocumentSyncKind.Incremental
                save = Either.forRight<Boolean, org.eclipse.lsp4j.SaveOptions>(org.eclipse.lsp4j.SaveOptions().apply { includeText = false })
            }
            textDocumentSync = Either.forRight<TextDocumentSyncKind, TextDocumentSyncOptions>(sync)
            hoverProvider = Either.forRight<Boolean, HoverOptions>(HoverOptions())
            definitionProvider = Either.forRight<Boolean, DefinitionOptions>(DefinitionOptions())
            documentSymbolProvider = Either.forRight<Boolean, DocumentSymbolOptions>(DocumentSymbolOptions())
            workspaceSymbolProvider = Either.forRight<Boolean, WorkspaceSymbolOptions>(WorkspaceSymbolOptions())
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun initialized(params: org.eclipse.lsp4j.InitializedParams) {
        // Indexing runs in background from initialize()
    }

    override fun shutdown(): CompletableFuture<Any> {
        indexManager?.close()
        documents.clear()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        Runtime.getRuntime().exit(0)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun setTrace(params: org.eclipse.lsp4j.SetTraceParams) {
        // Not needed yet.
    }

    fun dispose() {
        indexManager?.close()
        documents.clear()
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