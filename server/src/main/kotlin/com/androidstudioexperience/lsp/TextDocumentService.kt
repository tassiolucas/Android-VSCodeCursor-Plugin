package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.analysis.KotlinAnalyzer
import com.androidstudioexperience.lsp.analysis.SemanticTokensProvider
import com.androidstudioexperience.lsp.documents.DocumentManager
import com.androidstudioexperience.lsp.index.IndexManager
import com.androidstudioexperience.lsp.util.Uris
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class TextDocumentService(
    private val documents: DocumentManager,
    private val indexProvider: () -> IndexManager? = { null }
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val analyzer = KotlinAnalyzer()
    private val semanticTokens = SemanticTokensProvider(documents, analyzer, indexProvider) { uriToPath(it) }

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
            val name = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync null

            // 1. In-file declaration
            val inFile = analyzer.hoverOnDeclaration(uri, name)
            if (!inFile.isNullOrBlank()) {
                return@computeAsync Hover(MarkupContent(MarkupKind.MARKDOWN, inFile))
            }

            val filePath = uriToPath(uri)
            val indexManager = indexProvider()
            if (indexManager != null) {
                // 2. Project index (cross-file) — schema v2 carries signature+doc.
                if (filePath != null) {
                    val sym = indexManager.findDefinition(name, filePath)
                    if (sym != null) {
                        val signature = sym.signature ?: "${sym.kind.name.lowercase()} ${sym.name}"
                        val content = "```kotlin\n$signature\n```" +
                            (sym.doc?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: "") +
                            (sym.containerName?.let { "\n\n_In_ `${it}`" } ?: "") +
                            "\n\n_Declared in_ `${fileNameOf(sym.filePath)}`:${sym.line + 1}" +
                            (sym.moduleName?.let { "\n\n_Module_ `${it}`" } ?: "")
                        return@computeAsync Hover(MarkupContent(MarkupKind.MARKDOWN, content))
                    }
                }
                // 3. External library
                val lib = indexManager.findLibraryDefinition(name)
                if (lib != null) {
                    val ref = lib.memberName?.let { ".$it" } ?: ""
                    val signature = if (lib.memberDescriptor != null) {
                        com.androidstudioexperience.lsp.index.JvmSignatures.readableSignature(lib)
                    } else null
                    val content = "```kotlin\n${signature ?: "${lib.kind.name.lowercase()} ${lib.className.replace('/', '.')}$ref"}\n```" +
                        "\n\n_From library_ `${lib.artifact ?: lib.jarPath.substringAfterLast('/')}`"
                    return@computeAsync Hover(MarkupContent(MarkupKind.MARKDOWN, content))
                }
            }
            null
        }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            val name = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync null
            val indexManager = indexProvider()

            // 0. Android resources: R.<type>.<name> -> XML declaration.
            if (indexManager != null) {
                val lineText = text.lines().getOrNull(params.position.line)
                if (lineText != null) {
                    val resMatch = Regex("""\bR\.(\w+)\.(\w+)""").findAll(lineText).firstOrNull { m ->
                        val g = m.groups[2]!!
                        params.position.character in g.range
                    }
                    if (resMatch != null) {
                        val entries = indexManager.findResources(
                            resMatch.groupValues[1],
                            resMatch.groupValues[2],
                        )
                        if (entries.isNotEmpty()) {
                            logger.debug("Resource definition: R.${resMatch.groupValues[1]}.${resMatch.groupValues[2]} -> ${entries.size} entries")
                            return@computeAsync Either.forLeft(
                                entries.map { it.toLocation() }.toMutableList()
                            )
                        }
                    }
                }
            }

            // 1. In-file declaration (works for any identifier: calls, properties, types)
            val inFileLoc = analyzer.definitionOf(uri, name)
            if (inFileLoc != null) {
                logger.debug("Definition resolved in-file: $name")
                return@computeAsync Either.forLeft(mutableListOf(inFileLoc))
            }

            // 2. Cross-file via project index (multi-candidate when ambiguous:
            // overloads, expect/actual pairs, same name in different modules).
            val filePath = uriToPath(uri)
            if (indexManager != null && filePath != null) {
                val candidates = indexManager.findAllDefinitions(name, filePath)
                    .distinctBy { it.filePath + ":" + it.line }
                if (candidates.size == 1) {
                    logger.debug("Cross-file definition found: ${candidates[0].name} in ${candidates[0].filePath}")
                    return@computeAsync Either.forLeft(mutableListOf(candidates[0].toLocation()))
                } else if (candidates.size > 1) {
                    logger.debug("Ambiguous definition: $name -> ${candidates.size} candidates")
                    val links = candidates.map { sym ->
                        val targetRange = org.eclipse.lsp4j.Range(
                            org.eclipse.lsp4j.Position(sym.line, 0),
                            org.eclipse.lsp4j.Position(sym.line, 10000),
                        )
                        org.eclipse.lsp4j.LocationLink(
                            com.androidstudioexperience.lsp.index.SymbolInfo.filePathToUri(sym.filePath),
                            targetRange,
                            sym.toLocation().range,
                            sym.toLocation().range,
                        )
                    }
                    return@computeAsync Either.forRight(links.toMutableList())
                }
            }

            // 3. External library via the classpath index
            if (indexManager != null) {
                val lib = indexManager.findLibraryDefinition(name)
                if (lib != null) {
                    logger.debug("Library definition found: ${lib.name} in ${lib.className}")
                    return@computeAsync Either.forLeft(mutableListOf(lib.toLocation()))
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

    // ----- CodeLens ("N usages") + Inlay hints ---------------------------------

    override fun codeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync mutableListOf()
            val filePath = uriToPath(uri)
            val indexManager = indexProvider() ?: return@computeAsync mutableListOf()

            val lenses = mutableListOf<CodeLens>()
            for (decl in analyzer.allDeclarations(text)) {
                if (decl.kind !in LENSED_KINDS) continue
                val sym = indexManager.findAllDefinitions(decl.name, filePath)
                    .firstOrNull { it.filePath == filePath && it.line == decl.range.start.line }
                    ?: continue
                val count = indexManager.getStoredReferences(sym)
                    .count { !(it.filePath == sym.filePath && it.line == sym.line) }
                if (count == 0) continue
                val label = if (count == 1) "1 usage" else "$count usages"
                val command = org.eclipse.lsp4j.Command(
                    label,
                    "ase.showReferences",
                    listOf(
                        uri,
                        mapOf("line" to decl.nameRange.start.line, "character" to decl.nameRange.start.character),
                    ),
                )
                lenses += CodeLens(decl.nameRange, command, null)
            }
            lenses
        }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<MutableList<InlayHint>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync mutableListOf()
            val filePath = uriToPath(uri)
            val visible = params.range

            val maskedLines = com.androidstudioexperience.lsp.analysis.KotlinMask.maskLines(text)
            val lineStarts = run {
                val starts = mutableListOf(0)
                for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
                starts
            }

            fun offsetAt(line: Int, col: Int): Int =
                if (line < lineStarts.size) (lineStarts[line] + col).coerceAtMost(text.length) else text.length

            fun positionOf(offset: Int): Position {
                var lo = 0
                var hi = lineStarts.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
                }
                return Position(lo, offset - lineStarts[lo])
            }

            val callPattern = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""")
            val hints = mutableListOf<InlayHint>()
            val maxHints = 500

            val startLine = visible.start.line
            val endLine = visible.end.line.coerceAtMost(maskedLines.size - 1)
            for (lineIdx in startLine..endLine) {
                if (hints.size >= maxHints) break
                val mline = maskedLines[lineIdx]
                if (mline.isBlank()) continue
                for (m in callPattern.findAll(mline)) {
                    if (hints.size >= maxHints) break
                    val callee = m.groupValues[1]
                    if (callee in CALL_KEYWORDS) continue
                    // Skip declaration headers (`fun foo(`, `class Foo(`).
                    val decl = analyzer.symbolAt(uri, lineIdx, m.groups[1]!!.range.first)
                    if (decl != null && decl.isDeclaration) continue

                    val params = run {
                        val inFile = analyzer.declarationsByName(uri, callee)
                            .firstOrNull { it.parameters.isNotEmpty() }?.parameters
                        if (inFile != null) {
                            inFile
                        } else if (filePath != null) {
                            indexProvider()?.findAllDefinitions(callee, filePath)
                                ?.firstOrNull { it.parameters.isNotEmpty() }?.parameters
                        } else {
                            null
                        }
                    } ?: continue

                    // Split the argument list into top-level args.
                    // The regex match ends at the '(' itself.
                    val parenCol = m.range.last
                    if (mline.getOrNull(parenCol) != '(') continue
                    val openOffset = offsetAt(lineIdx, parenCol)
                    val args = splitArguments(text, maskedLines.joinToString("\n"), openOffset)
                    for ((argIdx, argRange) in args.withIndex()) {
                        if (argIdx >= params.size) break
                        val argText = text.substring(argRange.first, argRange.second).trim()
                        if (argText.isEmpty()) continue
                        if (Regex("""^\w+\s*=""").containsMatchIn(argText)) continue // named arg already
                        val param = params[argIdx]
                        if (param.name.length < 2) continue
                        // Align to the first non-blank char of the argument.
                        val argStartInText = argRange.first + (text.substring(argRange.first, argRange.second).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))
                        val hint = InlayHint(
                            positionOf(argStartInText),
                            Either.forLeft<String, MutableList<org.eclipse.lsp4j.InlayHintLabelPart>>("${param.name}:"),
                        )
                        hint.kind = org.eclipse.lsp4j.InlayHintKind.Parameter
                        hint.paddingRight = false
                        hint.paddingLeft = false
                        hints += hint
                    }
                }
            }
            hints
        }

    /** Top-level argument spans of the call whose '(' is at [openOffset]. */
    private fun splitArguments(text: String, masked: String, openOffset: Int): List<Pair<Int, Int>> {
        val args = mutableListOf<Pair<Int, Int>>()
        var paren = 0; var bracket = 0; var brace = 0; var angle = 0
        var start = openOffset + 1
        var i = openOffset + 1
        while (i < masked.length) {
            when (masked[i]) {
                '(' -> paren++
                ')' -> {
                    if (paren == 0) {
                        if (text.substring(start, i).isNotBlank()) args.add(start to i)
                        return args
                    }
                    paren--
                }
                '[' -> bracket++
                ']' -> bracket--
                '{' -> brace++
                '}' -> brace--
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                ',' -> if (paren == 0 && bracket == 0 && brace == 0 && angle == 0) {
                    args.add(start to i)
                    start = i + 1
                }
            }
            i++
        }
        return args
    }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<MutableList<out DocumentHighlight>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync mutableListOf()
            val ident = extractIdentifierAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync mutableListOf()
            val (name, declNameRange) = ident

            val declRange = analyzer.declarationsByName(uri, name).firstOrNull()?.nameRange
            val masked = com.androidstudioexperience.lsp.analysis.KotlinMask.maskLines(text)
            val pattern = Regex("\\b${Regex.escape(name)}\\b")
            val result = mutableListOf<DocumentHighlight>()
            for ((i, mline) in masked.withIndex()) {
                for (m in pattern.findAll(mline)) {
                    val range = Range(Position(i, m.range.first), Position(i, m.range.last + 1))
                    val isDecl = declRange != null && declRange.start.line == i &&
                        m.range.first == declRange.start.character
                    result += DocumentHighlight(
                        range,
                        if (isDecl) DocumentHighlightKind.Write else DocumentHighlightKind.Text,
                    )
                }
            }
            result
        }

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            val name = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync null
            val indexManager = indexProvider() ?: return@computeAsync null

            val impls = indexManager.findImplementations(name)
                .distinctBy { it.filePath + ":" + it.line }
                .map { it.toLocation() }
            if (impls.isEmpty()) null else Either.forLeft(impls.toMutableList())
        }

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            val name = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync null
            val filePath = uriToPath(uri)
            val indexManager = indexProvider() ?: return@computeAsync null

            // Resolve the symbol's declared type, then navigate to that type.
            val declaredType = analyzer.declarationsByName(uri, name)
                .firstOrNull { it.returnType != null }?.returnType
                ?: indexManager.findAllDefinitions(name, filePath)
                    .firstOrNull { it.returnType != null }?.returnType
                ?: return@computeAsync null
            val typeName = declaredType
                .substringBefore('<')
                .removeSuffix("?")
                .substringAfterLast('.')
                .trim()
            if (typeName.isEmpty()) return@computeAsync null

            val candidates = indexManager.findAllDefinitions(typeName, filePath)
                .distinctBy { it.filePath + ":" + it.line }
                .map { it.toLocation() }
            if (candidates.isEmpty()) null else Either.forLeft(candidates.toMutableList())
        }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            val ident = extractIdentifierAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync null
            val (name, range) = ident

            val filePath = uriToPath(uri)
            val resolvable = analyzer.declarationSymbol(uri, name) != null ||
                (filePath != null && indexProvider()?.findDefinition(name, filePath) != null) ||
                indexProvider()?.findLibraryDefinition(name) != null
            if (!resolvable) return@computeAsync null
            Either3.forFirst(range)
        }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            val ident = extractIdentifierAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync null
            val (name, _) = ident
            val newName = params.newName
            val filePath = uriToPath(uri)
            val indexManager = indexProvider()

            // Declaration site.
            val inFile = analyzer.declarationsByName(uri, name).firstOrNull()
            val indexed = if (filePath != null) indexManager?.findDefinition(name, filePath) else null
            if (inFile == null && indexed == null) return@computeAsync null

            val editsByFile = linkedMapOf<String, MutableList<TextEdit>>()
            fun addEdit(u: String, range: Range) {
                editsByFile.getOrPut(u) { mutableListOf() }.add(TextEdit(range, newName))
            }

            if (inFile != null) {
                addEdit(uri, inFile.nameRange)
            }
            if (indexed != null && indexManager != null) {
                addEdit(com.androidstudioexperience.lsp.index.SymbolInfo.filePathToUri(indexed.filePath), indexed.toLocation().range)
                // Text-based usages across the project (structural; may include
                // same-named false positives — documented limitation).
                for (ref in indexManager.findReferences(indexed)) {
                    addEdit(ref.toLocation().uri, ref.toLocation().range)
                }
            } else {
                // In-file only: every occurrence in the current document.
                val masked = com.androidstudioexperience.lsp.analysis.KotlinMask.maskLines(text)
                val pattern = Regex("\\b${Regex.escape(name)}\\b")
                for ((i, mline) in masked.withIndex()) {
                    for (m in pattern.findAll(mline)) {
                        addEdit(uri, Range(Position(i, m.range.first), Position(i, m.range.last + 1)))
                    }
                }
            }

            if (editsByFile.isEmpty()) return@computeAsync null
            WorkspaceEdit(editsByFile.mapValues { it.value })
        }

    // ----- Call hierarchy ------------------------------------------------------

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<MutableList<CallHierarchyItem>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync mutableListOf()
            val name = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync mutableListOf()
            val filePath = uriToPath(uri)

            // Prefer the declaration over a call-site.
            val inFile = analyzer.declarationsByName(uri, name)
                .firstOrNull { it.kind == SymbolKind.Function || it.kind == SymbolKind.Constructor }
            if (inFile != null) {
                return@computeAsync mutableListOf(
                    CallHierarchyItem(inFile.name, SymbolKind.Function, uri, inFile.range, inFile.nameRange)
                )
            }
            val sym = if (filePath != null) indexProvider()?.findAllDefinitions(name, filePath)
                ?.firstOrNull { it.kind == SymbolKind.Function || it.kind == SymbolKind.Constructor } else null
            if (sym != null) {
                val target = sym.toLocation()
                return@computeAsync mutableListOf(
                    CallHierarchyItem(sym.name, SymbolKind.Function, target.uri, target.range, target.range)
                )
            }
            mutableListOf()
        }

    override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<MutableList<CallHierarchyIncomingCall>> =
        CompletableFutures.computeAsync {
            val item = params.item
            val indexManager = indexProvider() ?: return@computeAsync mutableListOf()
            val fromPath = uriToPath(item.uri)
            val sym = indexManager.findAllDefinitions(item.name, fromPath)
                .firstOrNull { it.line == item.range.start.line }
                ?: indexManager.findAllDefinitions(item.name, fromPath).firstOrNull()
                ?: return@computeAsync mutableListOf()

            // Group usages by enclosing function.
            data class Key(val uri: String, val name: String, val line: Int, val endLine: Int)
            val groups = linkedMapOf<Key, MutableList<Range>>()
            for (ref in indexManager.findReferences(sym)) {
                val refUri = ref.toLocation().uri
                val refText = documents.get(refUri) ?: try {
                    java.io.File(ref.filePath).readText()
                } catch (e: Exception) { null } ?: continue
                val enclosing = analyzer.enclosingDeclaration(refText, ref.line)
                val key = Key(
                    refUri,
                    enclosing?.name ?: ref.filePath.substringAfterLast('/'),
                    enclosing?.range?.start?.line ?: 0,
                    enclosing?.range?.end?.line ?: 0,
                )
                groups.getOrPut(key) { mutableListOf() }.add(ref.toLocation().range)
            }

            groups.map { (key, ranges) ->
                val fromItem = CallHierarchyItem(
                    key.name,
                    SymbolKind.Function,
                    key.uri,
                    Range(Position(key.line, 0), Position(key.endLine, 10000)),
                    Range(Position(key.line, 0), Position(key.line, 10000)),
                )
                CallHierarchyIncomingCall(fromItem, ranges)
            }.toMutableList()
        }

    override fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture<MutableList<CallHierarchyOutgoingCall>> =
        CompletableFutures.computeAsync {
            val item = params.item
            val text = documents.get(item.uri) ?: try {
                uriToPath(item.uri)?.let { java.io.File(it).readText() }
            } catch (e: Exception) { null } ?: return@computeAsync mutableListOf()
            val filePath = uriToPath(item.uri)

            // The calls this function makes (references collected inside its body).
            val calls = analyzer.callsInFunction(text, item.name)
            val byCallee = calls.groupBy { it.name }
            val result = mutableListOf<CallHierarchyOutgoingCall>()
            for ((callee, refs) in byCallee) {
                val target = analyzer.declarationsByName(item.uri, callee).firstOrNull()
                    ?: if (filePath != null) indexProvider()?.findDefinition(callee, filePath) else null
                if (target == null) continue
                val (tUri, tRange, tSel) = when (target) {
                    is com.androidstudioexperience.lsp.analysis.Symbol ->
                        Triple(item.uri, target.range, target.nameRange)
                    else -> {
                        val loc = (target as com.androidstudioexperience.lsp.index.SymbolInfo).toLocation()
                        Triple(loc.uri, loc.range, loc.range)
                    }
                }
                result += CallHierarchyOutgoingCall(
                    CallHierarchyItem(callee, SymbolKind.Function, tUri, tRange, tSel),
                    refs.map { it.nameRange },
                )
            }
            result
        }

    // ----- Type hierarchy --------------------------------------------------------

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync mutableListOf()
            val name = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync mutableListOf()
            val filePath = uriToPath(uri)

            val inFile = analyzer.declarationsByName(uri, name).firstOrNull { it.kind in CLASS_LIKE }
            if (inFile != null) {
                return@computeAsync mutableListOf(
                    TypeHierarchyItem(inFile.name, inFile.kind, uri, inFile.range, inFile.nameRange)
                )
            }
            val sym = if (filePath != null) indexProvider()?.findAllDefinitions(name, filePath)
                ?.firstOrNull { it.kind in CLASS_LIKE } else null
            if (sym != null) {
                val loc = sym.toLocation()
                return@computeAsync mutableListOf(TypeHierarchyItem(sym.name, sym.kind, loc.uri, loc.range, loc.range))
            }
            mutableListOf()
        }

    override fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        CompletableFutures.computeAsync {
            val item = params.item
            val indexManager = indexProvider() ?: return@computeAsync mutableListOf()
            val fromPath = uriToPath(item.uri)
            val sym = indexManager.findAllDefinitions(item.name, fromPath)
                .firstOrNull { it.line == item.range.start.line }
                ?: indexManager.findAllDefinitions(item.name, fromPath).firstOrNull()
                ?: return@computeAsync mutableListOf()
            indexManager.findSuperTypes(sym)
                .distinctBy { it.filePath + ":" + it.line }
                .map { s ->
                    val loc = s.toLocation()
                    TypeHierarchyItem(s.name, s.kind, loc.uri, loc.range, loc.range)
                }
                .toMutableList()
        }

    override fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> =
        CompletableFutures.computeAsync {
            val item = params.item
            val indexManager = indexProvider() ?: return@computeAsync mutableListOf()
            indexManager.findImplementations(item.name)
                .distinctBy { it.filePath + ":" + it.line }
                .map { s ->
                    val loc = s.toLocation()
                    TypeHierarchyItem(s.name, s.kind, loc.uri, loc.range, loc.range)
                }
                .toMutableList()
        }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
        CompletableFutures.computeAsync { semanticTokens.computeFull(params.textDocument.uri) }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync mutableListOf()
            val name = extractSymbolAtPosition(text, params.position.line, params.position.character)
                ?: return@computeAsync mutableListOf()
            val filePath = uriToPath(uri)
            val indexManager = indexProvider() ?: return@computeAsync mutableListOf()

            // Resolve the declaration to anchor reference search.
            val sym = if (filePath != null) indexManager.findDefinition(name, filePath) else null
            if (sym == null) return@computeAsync mutableListOf()

            val refs = indexManager.findReferences(sym)
            val includeDeclaration = params.context?.isIncludeDeclaration ?: true
            refs.map { it.toLocation() }
                .filter {
                    includeDeclaration ||
                        !(it.range.start.line == sym.line && it.uri == com.androidstudioexperience.lsp.index.SymbolInfo.filePathToUri(sym.filePath))
                }
                .distinctBy { it.uri + ":" + it.range.start.line + ":" + it.range.start.character }
                .toMutableList()
        }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> =
        CompletableFutures.computeAsync {
            val uri = params.textDocument.uri
            val text = documents.get(uri) ?: return@computeAsync null
            val offset = documents.offsetAt(uri, params.position)
            val call = com.androidstudioexperience.lsp.analysis.CallSiteParser.parse(text, offset)
                ?: return@computeAsync null

            data class Candidate(val label: String, val params: List<String>, val doc: String?)

            fun paramLabel(p: com.androidstudioexperience.lsp.analysis.KotlinParam): String =
                p.name + (p.type?.let { ": $it" } ?: "") + (p.default?.let { " = $it" } ?: "")

            val candidates = mutableListOf<Candidate>()

            // 1. In-file declarations (functions, secondary ctors, classes w/ primary ctor).
            for (s in analyzer.declarationsByName(uri, call.calleeName)) {
                val isCallable = s.kind == SymbolKind.Function || s.kind == SymbolKind.Constructor ||
                    (s.kind == SymbolKind.Class && s.parameters.isNotEmpty())
                if (!isCallable) continue
                candidates += Candidate(s.signature, s.parameters.map(::paramLabel), s.doc)
            }

            val indexManager = indexProvider()
            val filePath = uriToPath(uri)
            if (indexManager != null) {
                // 2. Project index (schema v2 carries params).
                for (s in indexManager.findAllDefinitions(call.calleeName, filePath)) {
                    val isCallable = s.kind == SymbolKind.Function || s.kind == SymbolKind.Constructor ||
                        (s.kind == SymbolKind.Class && s.parameters.isNotEmpty())
                    if (!isCallable) continue
                    val label = s.signature ?: continue
                    candidates += Candidate(label, s.parameters.map(::paramLabel), s.doc)
                }
                // 3. Library members (JVM descriptors translated).
                for (lib in indexManager.findLibraryMembers(call.calleeName)) {
                    val desc = lib.memberDescriptor ?: continue
                    if (!desc.startsWith("(")) continue
                    val parsed = com.androidstudioexperience.lsp.index.JvmSignatures.parseMethod(desc)
                        ?: continue
                    val label = com.androidstudioexperience.lsp.index.JvmSignatures.readableSignature(lib)
                    candidates += Candidate(
                        label,
                        parsed.params.mapIndexed { i, t -> "p$i: $t" },
                        null,
                    )
                }
            }

            val distinct = candidates.distinctBy { it.label }
            if (distinct.isEmpty()) return@computeAsync null

            val signatures = distinct.map { c ->
                SignatureInformation(c.label).apply {
                    parameters = c.params.map { ParameterInformation(it) }
                    if (!c.doc.isNullOrBlank()) {
                        documentation = Either.forLeft<String, org.eclipse.lsp4j.MarkupContent>(c.doc)
                    }
                }
            }

            val active = call.activeParameter
            val activeSigIdx = distinct
                .indexOfFirst { it.params.size > active }
                .let { if (it == -1) 0 else it }
            val activeParamMax = signatures[activeSigIdx].parameters.size - 1

            SignatureHelp().apply {
                this.signatures = signatures
                this.activeSignature = activeSigIdx
                this.activeParameter = if (activeParamMax < 0) 0 else active.coerceAtMost(activeParamMax)
            }
        }

    private fun uriToPath(uri: String): String? = Uris.uriToPath(uri)

    private fun fileNameOf(filePath: String): String =
        filePath.substringAfterLast('/').substringAfterLast('\\')

    /** Returns (identifier, its range) at the position, or null. */
    private fun extractIdentifierAtPosition(text: String, line: Int, col: Int): Pair<String, Range>? {
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
        if (ident.isBlank()) return null
        return ident to Range(Position(line, start), Position(line, end))
    }

    private fun extractSymbolAtPosition(text: String, line: Int, col: Int): String? =
        extractIdentifierAtPosition(text, line, col)?.first

    companion object {
        private val CLASS_LIKE = setOf(
            SymbolKind.Class, SymbolKind.Interface, SymbolKind.Object, SymbolKind.Enum,
        )

        private val LENSED_KINDS = setOf(
            SymbolKind.Class, SymbolKind.Interface, SymbolKind.Object, SymbolKind.Enum,
            SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor, SymbolKind.Property,
        )

        private val CALL_KEYWORDS = setOf(
            "if", "for", "while", "when", "return", "catch", "constructor", "init",
            "class", "interface", "object", "fun", "super", "this", "get", "set",
        )
    }
}