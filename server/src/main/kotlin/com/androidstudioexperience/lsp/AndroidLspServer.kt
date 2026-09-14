package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.documents.DocumentManager
import com.androidstudioexperience.lsp.index.IndexManager
import com.androidstudioexperience.lsp.util.Uris
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

        // Client settings passed at initialize (contributed configuration).
        val initOptions = params.initializationOptions as? com.google.gson.JsonObject
        val includeGeneratedSources = initOptions?.get("generatedSources")?.asBoolean ?: true

        // Initialize index manager if we have a workspace root
        if (rootUri != null) {
            val rootPath = Uris.uriToPath(rootUri!!)
            logger.info("Workspace root: $rootUri -> $rootPath")
            if (rootPath != null) {
                indexManager = IndexManager.create(rootPath, includeGeneratedSources)
                if (indexManager != null) {
                    logger.info("Index manager initialized for $rootPath")
                    // Start background indexing
                    CompletableFuture.runAsync {
                        try {
                            val count = indexManager!!.indexProject(rootPath)
                            logger.info("Background indexing complete: $count symbols")
                            val libCount = indexManager!!.indexExternalLibraries(rootPath)
                            client?.let { c ->
                                c.showMessage(org.eclipse.lsp4j.MessageParams(
                                    org.eclipse.lsp4j.MessageType.Info,
                                    "ASE: Indexed $count symbols (project) + $libCount (libraries)"
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
            referencesProvider = Either.forRight<Boolean, org.eclipse.lsp4j.ReferenceOptions>(org.eclipse.lsp4j.ReferenceOptions())
            signatureHelpProvider = org.eclipse.lsp4j.SignatureHelpOptions(
                listOf("(", ","),
                listOf(","),
            )
            documentHighlightProvider = Either.forRight<Boolean, org.eclipse.lsp4j.DocumentHighlightOptions>(
                org.eclipse.lsp4j.DocumentHighlightOptions()
            )
            implementationProvider = Either.forRight<Boolean, org.eclipse.lsp4j.ImplementationRegistrationOptions>(
                org.eclipse.lsp4j.ImplementationRegistrationOptions()
            )
            typeDefinitionProvider = Either.forRight<Boolean, org.eclipse.lsp4j.TypeDefinitionRegistrationOptions>(
                org.eclipse.lsp4j.TypeDefinitionRegistrationOptions()
            )
            renameProvider = Either.forRight<Boolean, org.eclipse.lsp4j.RenameOptions>(
                org.eclipse.lsp4j.RenameOptions(true)
            )
            callHierarchyProvider = Either.forRight<Boolean, org.eclipse.lsp4j.CallHierarchyRegistrationOptions>(
                org.eclipse.lsp4j.CallHierarchyRegistrationOptions()
            )
            typeHierarchyProvider = Either.forRight<Boolean, org.eclipse.lsp4j.TypeHierarchyRegistrationOptions>(
                org.eclipse.lsp4j.TypeHierarchyRegistrationOptions()
            )
            codeLensProvider = org.eclipse.lsp4j.CodeLensOptions(false)
            inlayHintProvider = Either.forRight<Boolean, org.eclipse.lsp4j.InlayHintRegistrationOptions>(
                org.eclipse.lsp4j.InlayHintRegistrationOptions()
            )
            executeCommandProvider = org.eclipse.lsp4j.ExecuteCommandOptions(listOf("ase.decompile"))
            semanticTokensProvider = org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions(
                com.androidstudioexperience.lsp.analysis.SemanticTokensProvider.LEGEND,
                org.eclipse.lsp4j.SemanticTokensServerFull(true)
            )
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
}