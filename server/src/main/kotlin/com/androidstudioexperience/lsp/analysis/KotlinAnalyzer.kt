package com.androidstudioexperience.lsp.analysis

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind

/** A parsed Kotlin parameter (function or constructor). */
data class KotlinParam(
    val name: String,
    val type: String? = null,
    val default: String? = null,
)

/**
 * Structural Kotlin analyzer (regex/line based, comment-and-string aware).
 *
 * Milestone B rewrite: hierarchical document model. Declarations form a tree
 * (classes contain members, constructors expose val/var properties, enums
 * expose entries) and every symbol carries its full signature, doc,
 * parameters, return type, super types, visibility, package and container.
 *
 * ponytail: structural, not semantic. The public surface
 * (hover/definition/documentSymbols/declarationSymbol/findSymbols) is kept
 * stable so the LSP layer does not change when the engine is swapped.
 */
class KotlinAnalyzer {

    private val openDocuments = mutableMapOf<String, DocumentModel>()

    fun onDocumentOpened(uri: String, text: String) {
        openDocuments[uri] = DocumentModel.parse(text)
    }

    fun onDocumentChanged(uri: String, text: String) {
        openDocuments[uri] = DocumentModel.parse(text)
    }

    fun onDocumentClosed(uri: String) {
        openDocuments.remove(uri)
    }

    fun hover(uri: String, text: String, line: Int, character: Int): String? {
        val model = openDocuments[uri] ?: DocumentModel.parse(text)
        val symbol = model.symbolAt(line, character)
            ?: model.resolveReference(identifierAt(text, line, character) ?: return null)
            ?: return null
        val fileName = uri.substringAfterLast('/').substringAfterLast('\\')
        return buildHover(symbol.copy(fileName = fileName))
    }

    fun definition(uri: String, text: String, line: Int, character: Int): Location? {
        val model = openDocuments[uri] ?: DocumentModel.parse(text)
        val symbol = model.symbolAt(line, character)

        if (symbol != null && symbol.isDeclaration && symbol.range.start.line == line &&
            character >= symbol.nameRange.start.character && character <= symbol.nameRange.end.character
        ) {
            return locationFor(uri, symbol)
        }

        val name = symbol?.name ?: identifierAt(text, line, character) ?: return null
        val target = model.resolveReference(name) ?: return null
        return locationFor(uri, target)
    }

    fun documentSymbols(text: String): List<DocumentSymbol> =
        DocumentModel.parse(text).toDocumentSymbols()

    /** Top-level parsed symbols (full detail) of a document — used by the indexer. */
    fun topLevelSymbols(text: String): List<Symbol> =
        DocumentModel.parse(text).topLevel

    /** All declarations (flattened, including nested members). */
    fun allDeclarations(text: String): List<Symbol> =
        DocumentModel.parse(text).allDeclarations

    fun findSymbols(text: String, query: String): List<SymbolInformation> =
        DocumentModel.parse(text).allDeclarations
            .filter { it.name.lowercase().contains(query.lowercase()) }
            .map { it.toSymbolInformation() }

    /**
     * Resolves a declaration in the currently open document by exact name
     * (first match). Used by the universal definition/hover chain and the
     * semantic tokens provider.
     */
    fun declarationSymbol(uri: String, name: String): Symbol? =
        openDocuments[uri]?.declarations?.get(name)?.firstOrNull()

    /** All in-file declarations with this simple name (overloads, same-named members). */
    fun declarationsByName(uri: String, name: String): List<Symbol> =
        openDocuments[uri]?.declarations?.get(name) ?: emptyList()

    /** In-file definition for any identifier (declaration lookup by name). */
    fun definitionOf(uri: String, name: String): Location? =
        declarationSymbol(uri, name)?.let { locationFor(uri, it) }

    /** Hover markdown for an in-file declaration (signature + doc + location). */
    fun hoverOnDeclaration(uri: String, name: String): String? =
        declarationSymbol(uri, name)?.let {
            buildHover(it.copy(fileName = uri.substringAfterLast('/').substringAfterLast('\\')))
        }

    /** Symbol (declaration or call reference) at the given position. */
    fun symbolAt(uri: String, line: Int, character: Int): Symbol? =
        openDocuments[uri]?.symbolAt(line, character)

    /** Call references collected for the document (for call hierarchy / usages). */
    fun referencesOf(uri: String): List<Symbol> =
        openDocuments[uri]?.references ?: emptyList()

    /** Innermost declaration (function/class) whose span contains [line]. */
    fun enclosingDeclaration(text: String, line: Int): Symbol? =
        DocumentModel.parse(text).allDeclarations
            .filter { line in it.range.start.line..it.range.end.line }
            .maxByOrNull { it.range.start.line }

    /** All call references of [text] whose enclosing declaration is [funName]. */
    fun callsInFunction(text: String, funName: String): List<Symbol> {
        val model = DocumentModel.parse(text)
        return model.references.filter { ref ->
            ref.containerName == funName ||
                ref.containerName?.endsWith(".$funName") == true
        }
    }

    /** Package name declared in the document, if any. */
    fun packageOf(uri: String): String? =
        openDocuments[uri]?.packageName

    // ----- helpers ------------------------------------------------------------

    private fun buildHover(symbol: Symbol): String {
        val sb = StringBuilder()
        sb.append("```kotlin\n")
        sb.append(symbol.signature)
        sb.append("\n```")
        if (!symbol.doc.isNullOrBlank()) {
            sb.append("\n\n")
            sb.append(symbol.doc)
        }
        if (symbol.isDeclaration) {
            sb.append("\n\n_Declared in_ `${symbol.fileName}`:${symbol.range.start.line + 1}")
        }
        return sb.toString()
    }

    private fun locationFor(uri: String, symbol: Symbol): Location =
        Location(uri, symbol.range)

    companion object {
        /** Expands the identifier at (line, character) in raw [text]. */
        fun identifierAt(text: String, line: Int, col: Int): String? {
            val lines = text.lines()
            if (line < 0 || line >= lines.size) return null
            val lineText = lines[line]
            if (col < 0 || col >= lineText.length) return null
            if (!lineText[col].isLetterOrDigit() && lineText[col] != '_') return null
            var start = col
            while (start > 0 && (lineText[start - 1].isLetterOrDigit() || lineText[start - 1] == '_')) start--
            var end = col
            while (end < lineText.length && (lineText[end].isLetterOrDigit() || lineText[end] == '_')) end++
            return lineText.substring(start, end).ifBlank { null }
        }
    }
}

// -----------------------------------------------------------------------------
// Document model
// -----------------------------------------------------------------------------

data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val signature: String,
    val range: Range,
    val nameRange: Range,
    val isDeclaration: Boolean,
    val doc: String? = null,
    val fileName: String = "Unknown.kt",
    val qualifiedName: String = name,
    val containerName: String? = null,
    val visibility: String? = null,
    val parameters: List<KotlinParam> = emptyList(),
    val returnType: String? = null,
    val superTypes: List<String> = emptyList(),
    val packageName: String? = null,
    val children: List<Symbol> = emptyList(),
) {
    fun toSymbolInformation(): SymbolInformation =
        SymbolInformation(name, kind, org.eclipse.lsp4j.Location("file:///$fileName", range), containerName)
}

data class DocumentModel(
    val declarations: Map<String, List<Symbol>>,
    val references: List<Symbol>,
    val topLevel: List<Symbol>,
    val packageName: String?,
) {
    val allDeclarations: List<Symbol> = flatten(topLevel)

    private val symbolsSorted: List<Symbol> =
        (allDeclarations + references).sortedBy { it.range.start.line }

    fun symbolAt(line: Int, character: Int): Symbol? =
        symbolsSorted.firstOrNull { s ->
            s.nameRange.start.line == line &&
                character >= s.nameRange.start.character &&
                character <= s.nameRange.end.character
        }

    fun resolveReference(name: String): Symbol? = declarations[name]?.firstOrNull()

    fun toDocumentSymbols(): List<DocumentSymbol> =
        topLevel.map { toDocumentSymbol(it) }

    companion object {
        fun flatten(symbols: List<Symbol>): List<Symbol> =
            symbols.flatMap { listOf(it) + flatten(it.children) }

        private fun toDocumentSymbol(s: Symbol): DocumentSymbol =
            DocumentSymbol(
                s.name,
                s.kind,
                s.range,
                s.nameRange,
                s.signature,
                s.children.map { toDocumentSymbol(it) },
            )

        fun parse(text: String): DocumentModel = Parser(text).parse()
    }
}

// -----------------------------------------------------------------------------
// Parser
// -----------------------------------------------------------------------------

private class Parser(text: String) {

    private val originalLines: List<String> = text.lines()
    private val maskedLines: List<String> = KotlinMask.maskLines(text)
    private val depthStart: IntArray
    private val depthEnd: IntArray

    private val references = mutableListOf<Symbol>()
    private var packageName: String? = null

    /** Mutable declaration node; converted to immutable [Symbol] at the end. */
    private class Node(
        val name: String,
        val nameCol: Int,
        val kind: SymbolKind,
        val startLine: Int,
        val headerEndLine: Int,
        val signature: String,
        val doc: String?,
        val visibility: String?,
        val parameters: List<KotlinParam>,
        val returnType: String?,
        val superTypes: List<String>,
        val parent: Node?,
    ) {
        val children = mutableListOf<Node>()
        var bodyEndLine: Int = headerEndLine
        var symbol: Symbol? = null
    }

    private data class PendingBody(val node: Node, val bodyDepth: Int, val isEnum: Boolean, var seenSemi: Boolean)

    private val root = Node(
        name = "", nameCol = 0, kind = SymbolKind.File, startLine = -1, headerEndLine = -1,
        signature = "", doc = null, visibility = null, parameters = emptyList(),
        returnType = null, superTypes = emptyList(), parent = null,
    )
    private val bodyStack = ArrayDeque<PendingBody>()

    init {
        val n = originalLines.size
        depthStart = IntArray(n + 1)
        depthEnd = IntArray(n + 1)
        var depth = 0
        for (i in 0 until n) {
            depthStart[i] = depth
            for (ch in maskedLines[i]) {
                if (ch == '{') depth++ else if (ch == '}') depth--
            }
            if (depth < 0) depth = 0 // defensive: unbalanced file
            depthEnd[i] = depth
        }
        depthStart[n] = depth
        depthEnd[n] = depth
    }

    fun parse(): DocumentModel {
        var currentDoc: String? = null
        var i = 0
        val n = originalLines.size

        while (i < n) {
            val masked = maskedLines[i]
            val trimmed = masked.trim()

            // Close containers whose body ended before this line.
            while (bodyStack.isNotEmpty() && depthStart[i] < bodyStack.last().bodyDepth) {
                val closed = bodyStack.removeLast()
                closed.node.bodyEndLine = (i - 1).coerceAtLeast(closed.node.headerEndLine)
            }

            // --- doc tracking (on original text) ------------------------------
            val origTrimmed = originalLines[i].trim()
            if (origTrimmed.startsWith("/*")) {
                currentDoc = collectBlockDoc(originalLines, i)
            } else if (origTrimmed.startsWith("//")) {
                currentDoc = origTrimmed.removePrefix("//").trim()
            } else if (origTrimmed.startsWith("@")) {
                // annotation line: keep doc for the declaration below
            } else if (origTrimmed.startsWith("*")) {
                // inside a KDoc block: keep collecting
            } else if (origTrimmed.isEmpty()) {
                currentDoc = null
            }

            if (packageName == null) {
                PACKAGE.find(masked)?.let { packageName = it.groupValues[1] }
            }

            // --- enum entries ---------------------------------------------------
            val top = bodyStack.lastOrNull()
            if (top != null && top.isEnum && !top.seenSemi && trimmed.isNotEmpty()) {
                if (trimmed.contains(';')) top.seenSemi = true
                val entries = parseEnumEntries(masked, i, top.node)
                if (entries.isNotEmpty()) {
                    top.node.children.addAll(entries)
                    currentDoc = null
                    i++
                    continue
                }
            }

            // --- declarations ---------------------------------------------------
            val decl = matchDeclaration(i, currentDoc)
            if (decl != null) {
                currentDoc = null
                if (decl.opensBody) {
                    bodyStack.addLast(
                        PendingBody(
                            decl.node, decl.bodyDepth,
                            isEnum = decl.node.kind == SymbolKind.Enum, seenSemi = false,
                        )
                    )
                }
                i = decl.headerEndLine + 1
                continue
            }

            // --- call-site references -------------------------------------------
            collectCallReferences(i)
            i++
        }

        // EOF: close any remaining containers.
        while (bodyStack.isNotEmpty()) {
            val closed = bodyStack.removeLast()
            closed.node.bodyEndLine = n - 1
        }

        // Convert node tree to immutable symbols.
        val topLevel = root.children.map { buildSymbol(it) }
        val allDecls = mutableListOf<Symbol>()
        fun collect(symbols: List<Symbol>) {
            for (s in symbols) {
                allDecls.add(s)
                collect(s.children)
            }
        }
        collect(topLevel)

        val byName = LinkedHashMap<String, MutableList<Symbol>>()
        for (s in allDecls) byName.getOrPut(s.name) { mutableListOf() }.add(s)
        return DocumentModel(byName, references, topLevel, packageName)
    }

    private fun buildSymbol(node: Node): Symbol {
        val parentSymbol = node.parent?.takeIf { it !== root }?.symbol
        val qName = if (parentSymbol != null) "${parentSymbol.qualifiedName}.${node.name}" else node.name
        val lineLen = originalLines.getOrNull(node.startLine)?.length ?: 0
        val endCol = (node.nameCol + node.name.length).coerceAtMost(lineLen.coerceAtLeast(node.nameCol))
        val symbol = Symbol(
            name = node.name,
            kind = node.kind,
            signature = node.signature,
            range = Range(Position(node.startLine, 0), Position(node.bodyEndLine, 10000)),
            nameRange = Range(
                Position(node.startLine, node.nameCol),
                Position(node.startLine, endCol),
            ),
            isDeclaration = true,
            doc = node.doc,
            qualifiedName = qName,
            containerName = parentSymbol?.name,
            visibility = node.visibility,
            parameters = node.parameters,
            returnType = node.returnType,
            superTypes = node.superTypes,
            packageName = packageName,
        )
        node.symbol = symbol
        val children = node.children.map { buildSymbol(it) }
        return symbol.copy(children = children)
    }

    // --- declaration matching -------------------------------------------------

    private inner class RawDecl(
        val node: Node,
        val headerEndLine: Int,
        val opensBody: Boolean,
        val bodyDepth: Int,
    )

    private fun matchDeclaration(lineIdx: Int, doc: String?): RawDecl? {
        val line = maskedLines[lineIdx]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
        val parent = bodyStack.lastOrNull()?.node

        // fun interface is a keyword form that FUN would misinterpret.
        if (trimmed.contains("fun interface ")) {
            val m = Regex("""fun interface\s+(\w+)""").find(trimmed) ?: return null
            val headerEnd = assembleHeaderEnd(lineIdx, trimmed)
            val header = headerText(lineIdx, headerEnd)
            val headerOrig = headerTextOriginal(lineIdx, headerEnd)
            val node = Node(
                name = m.groupValues[1],
                nameCol = indent + m.groups[1]!!.range.first,
                kind = SymbolKind.Interface,
                startLine = lineIdx, headerEndLine = headerEnd,
                signature = signatureFrom(header, headerOrig, '{'),
                doc = doc, visibility = visibilityOf(trimmed),
                parameters = emptyList(), returnType = null, superTypes = emptyList(),
                parent = parent,
            )
            return finish(node, headerEnd, header)
        }

        val funMatch = FUN.find(trimmed)
        if (funMatch != null) {
            val headerEnd = assembleHeaderEnd(lineIdx, trimmed)
            val header = headerText(lineIdx, headerEnd)
            val headerOrig = headerTextOriginal(lineIdx, headerEnd)
            val parenBlock = extractParenBlock(header)
            val node = Node(
                name = funMatch.groupValues[1],
                nameCol = indent + funMatch.groups[1]!!.range.first,
                kind = SymbolKind.Function,
                startLine = lineIdx, headerEndLine = headerEnd,
                signature = signatureFrom(header, headerOrig, '{', '='),
                doc = doc, visibility = visibilityOf(trimmed),
                parameters = parenBlock?.let { parseParams(it).map { p -> p.param } } ?: emptyList(),
                returnType = returnTypeOf(header),
                superTypes = emptyList(),
                parent = parent,
            )
            return finish(node, headerEnd, header)
        }

        val classLike = CLASS_LIKE.find(trimmed)
        if (classLike != null) {
            val rawName = classLike.groupValues[2]
            val name = rawName.ifEmpty { "Companion" } // `companion object {`
            val nameCol = if (rawName.isEmpty()) {
                indent + classLike.groups[1]!!.range.first
            } else {
                indent + classLike.groups[2]!!.range.first
            }
            val headerEnd = assembleHeaderEnd(lineIdx, trimmed)
            val header = headerText(lineIdx, headerEnd)
            val headerOrig = headerTextOriginal(lineIdx, headerEnd)
            val kind = when {
                Regex("""\benum\s+class\b""").containsMatchIn(header) -> SymbolKind.Enum
                classLike.groupValues[1] == "interface" -> SymbolKind.Interface
                classLike.groupValues[1] == "object" -> SymbolKind.Object
                else -> SymbolKind.Class
            }
            val node = Node(
                name = name,
                nameCol = nameCol,
                kind = kind,
                startLine = lineIdx, headerEndLine = headerEnd,
                signature = signatureFrom(header, headerOrig, '{').removeSuffix(":").trim(),
                doc = doc, visibility = visibilityOf(trimmed),
                parameters = extractParenBlock(header)?.let { parseParams(it).map { p -> p.param } } ?: emptyList(),
                returnType = null,
                superTypes = superTypesOf(header, kind),
                parent = parent,
            )
            // Constructor val/var params become Property children of the class.
            val parenBlock = extractParenBlock(header)
            if (parenBlock != null) {
                for (p in parseParams(parenBlock)) {
                    if (!p.isProperty) continue
                    val propLine = locateCtorPropLine(lineIdx, headerEnd, p.param.name)
                    val col = maskedLines[propLine].indexOf(p.param.name).coerceAtLeast(0)
                    node.children.add(
                        Node(
                            name = p.param.name, nameCol = col, kind = SymbolKind.Property,
                            startLine = propLine, headerEndLine = propLine,
                            signature = "val ${p.param.name}${p.param.type?.let { ": $it" } ?: ""}",
                            doc = null, visibility = visibilityOf(maskedLines[propLine].trim()),
                            parameters = emptyList(), returnType = p.param.type,
                            superTypes = emptyList(), parent = node,
                        )
                    )
                }
            }
            parent?.children?.add(node) ?: root.children.add(node)
            return finish(node, headerEnd, header, alreadyAttached = true)
        }

        val aliasMatch = TYPEALIAS.find(trimmed)
        if (aliasMatch != null) {
            val headerEnd = assembleHeaderEnd(lineIdx, trimmed)
            val headerOrig = headerTextOriginal(lineIdx, headerEnd)
            val node = Node(
                name = aliasMatch.groupValues[1],
                nameCol = indent + aliasMatch.groups[1]!!.range.first,
                kind = SymbolKind.Class,
                startLine = lineIdx, headerEndLine = headerEnd,
                signature = headerOrig,
                doc = doc, visibility = visibilityOf(trimmed),
                parameters = emptyList(), returnType = null, superTypes = emptyList(),
                parent = parent,
            )
            return finish(node, headerEnd, headerText(lineIdx, headerEnd))
        }

        // Secondary constructor (only meaningful inside a class body).
        val ctor = CONSTRUCTOR.find(trimmed)
        if (ctor != null && parent != null && parent.kind in CLASS_LIKE_KINDS) {
            val headerEnd = assembleHeaderEnd(lineIdx, trimmed)
            val header = headerText(lineIdx, headerEnd)
            val headerOrig = headerTextOriginal(lineIdx, headerEnd)
            val node = Node(
                name = parent.name,
                nameCol = indent + ctor.range.first,
                kind = SymbolKind.Constructor,
                startLine = lineIdx, headerEndLine = headerEnd,
                signature = signatureFrom(header, headerOrig, '{'),
                doc = doc, visibility = visibilityOf(trimmed),
                parameters = extractParenBlock(header)?.let { parseParams(it).map { p -> p.param } } ?: emptyList(),
                returnType = null, superTypes = emptyList(),
                parent = parent,
            )
            return finish(node, headerEnd, header)
        }

        val valVar = VAL_VAR.find(trimmed)
        if (valVar != null) {
            // Only properties: top-level or directly in a class-like body.
            val inClassBody = parent != null && parent.kind in CLASS_LIKE_KINDS
            if (parent != null && !inClassBody) return null // local inside function: skip
            val headerEnd = assembleHeaderEnd(lineIdx, trimmed)
            val header = headerText(lineIdx, headerEnd)
            val headerOrig = headerTextOriginal(lineIdx, headerEnd)
            val node = Node(
                name = valVar.groupValues[1],
                nameCol = indent + valVar.groups[1]!!.range.first,
                kind = SymbolKind.Property,
                startLine = lineIdx, headerEndLine = headerEnd,
                signature = signatureFrom(header, headerOrig, '=', '{')
                    .replace(Regex("""\s+by$"""), "").trim(),
                doc = doc, visibility = visibilityOf(trimmed),
                parameters = emptyList(),
                returnType = propertyTypeOf(header),
                superTypes = emptyList(),
                parent = parent,
            )
            return finish(node, headerEnd, header)
        }

        return null
    }

    // --- header assembly --------------------------------------------------------

    /**
     * Finds the last line of a (possibly multi-line) declaration header:
     * advances until parentheses opened in the header are balanced.
     */
    private fun assembleHeaderEnd(startLine: Int, trimmedStart: String): Int {
        var balance = 0
        var seenParen = false
        var i = startLine
        while (i < maskedLines.size) {
            val line = if (i == startLine) trimmedStart else maskedLines[i]
            for (ch in line) {
                if (ch == '(') { balance++; seenParen = true }
                else if (ch == ')') balance--
            }
            if (!seenParen) {
                // No parens at all (e.g. `object Foo {`): header is one line...
                // ...but the body brace may be on the NEXT line (`class A\n{`).
                val next = startLine + 1
                return if (next < maskedLines.size && maskedLines[next].trim().startsWith("{")) next else startLine
            }
            if (balance <= 0) {
                val next = i + 1
                return if (next < maskedLines.size && maskedLines[next].trim().startsWith("{")) next else i
            }
            i++
        }
        return maskedLines.size - 1
    }

    private fun headerText(startLine: Int, endLine: Int): String {
        if (startLine == endLine) return maskedLines[startLine].trim()
        return (startLine..endLine).joinToString(" ") { maskedLines[it].trim() }.trim()
    }

    /**
     * Same assembly as [headerText] but over ORIGINAL lines — same length,
     * same columns (the mask preserves positions), so a cut index computed on
     * the masked header maps 1:1 to the original. Used for display signatures
     * (strings/defaults must show real text, not masked blanks).
     */
    private fun headerTextOriginal(startLine: Int, endLine: Int): String {
        if (startLine == endLine) return originalLines[startLine].trim()
        return (startLine..endLine).joinToString(" ") { originalLines[it].trim() }.trim()
    }

    /** Index of the first top-level (paren/angle depth 0) occurrence of [cut], or -1. */
    private fun topLevelIndexOf(header: String, cut: Char): Int {
        var paren = 0
        var angle = 0
        for (i in header.indices) {
            when (header[i]) {
                '(' -> paren++
                ')' -> paren--
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                else -> if (header[i] == cut && paren == 0 && angle == 0) return i
            }
        }
        return -1
    }

    /**
     * Display signature: cut the ORIGINAL header at the earliest top-level
     * occurrence of [cuts] found in the MASKED header (so braces/equals inside
     * string literals don't cut, but their text survives in the output).
     */
    private fun signatureFrom(headerMasked: String, headerOrig: String, vararg cuts: Char): String {
        var idx = headerMasked.length
        for (c in cuts) {
            val i = topLevelIndexOf(headerMasked, c)
            if (i >= 0 && i < idx) idx = i
        }
        return headerOrig.substring(0, minOf(idx, headerOrig.length)).trim()
    }

    /** Cuts [header] at the first top-level (paren/angle depth 0) occurrence of [cut]. */
    private fun cutTopLevel(header: String, cut: Char): String {
        var paren = 0
        var angle = 0
        for (i in header.indices) {
            when (header[i]) {
                '(' -> paren++
                ')' -> paren--
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                else -> if (header[i] == cut && paren == 0 && angle == 0) return header.substring(0, i)
            }
        }
        return header
    }

    private fun headerContainsTopLevelBrace(header: String): Boolean =
        cutTopLevel(header, '{').length != header.length

    /** Node + body bookkeeping shared by all declaration forms. */
    private fun finish(node: Node, headerEnd: Int, header: String, alreadyAttached: Boolean = false): RawDecl {
        if (!alreadyAttached) {
            node.parent?.children?.add(node) ?: root.children.add(node)
        }
        val opensBody = headerContainsTopLevelBrace(header) ||
            (headerEnd > node.startLine && maskedLines.getOrNull(headerEnd)?.trim()?.startsWith("{") == true)
        val bodyDepth = if (opensBody) {
            depthEnd[headerEnd].coerceAtLeast(depthStart[headerEnd] + 1)
        } else 0
        return RawDecl(node, headerEnd, opensBody, bodyDepth)
    }

    /** Extracts the first top-level (...) block of the header (params/ctor args). */
    private fun extractParenBlock(header: String): String? {
        val open = header.indexOf('(')
        if (open == -1) return null
        var depth = 0
        for (i in open until header.length) {
            when (header[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return header.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private class ParsedParam(val param: KotlinParam, val isProperty: Boolean)

    private fun parseParams(block: String): List<ParsedParam> {
        val parts = splitTopLevel(block, ',')
        val result = mutableListOf<ParsedParam>()
        for (raw in parts) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val isProp = Regex("""\b(val|var)\b""").containsMatchIn(part)
            val m = Regex("""(?:@[\w.:]+(?:\([^()]*\))?\s+)*(?:(?:vararg|noinline|crossinline|val|var)\s+)*(\w+)\s*(?::\s*(.*?))?\s*(?:=\s*(.*))?$""")
                .find(part) ?: continue
            val name = m.groupValues[1]
            if (name == "val" || name == "var") continue
            val type = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.trim()
            val default = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.trim()
            result.add(ParsedParam(KotlinParam(name, type, default), isProp))
        }
        return result
    }

    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val parts = mutableListOf<String>()
        var paren = 0; var angle = 0; var bracket = 0; var brace = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> paren++
                ')' -> paren--
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '[' -> bracket++
                ']' -> bracket--
                '{' -> brace++
                '}' -> brace--
                else -> if (s[i] == sep && paren == 0 && angle == 0 && bracket == 0 && brace == 0) {
                    parts.add(s.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(s.substring(start))
        return parts
    }

    private fun returnTypeOf(header: String): String? {
        var paren = 0
        var lastClose = -1
        for (i in header.indices) {
            if (header[i] == '(') paren++
            else if (header[i] == ')') { paren--; if (paren == 0) lastClose = i }
        }
        if (lastClose == -1) return null
        val rest = header.substring(lastClose + 1).trim()
        if (!rest.startsWith(":")) return null
        val typePart = rest.removePrefix(":").trim()
        val cut = listOf(typePart.indexOf('='), typePart.indexOf('{'))
            .filter { it >= 0 }.minOrNull() ?: typePart.length
        return typePart.substring(0, cut).trim().ifBlank { null }
    }

    private fun propertyTypeOf(header: String): String? {
        val colon = header.indexOf(':')
        if (colon == -1) return null
        val rest = header.substring(colon + 1)
        val byIdx = Regex("""\sby\s""").find(rest)?.range?.first ?: -1
        val cuts = listOf(rest.indexOf('='), rest.indexOf('{'), byIdx).filter { it >= 0 }
        val end = cuts.minOrNull() ?: rest.length
        return rest.substring(0, end).trim().ifBlank { null }
    }

    private fun superTypesOf(header: String, kind: SymbolKind): List<String> {
        if (kind == SymbolKind.Function || kind == SymbolKind.Property) return emptyList()
        var paren = 0
        var colon = -1
        var i = 0
        while (i < header.length) {
            when (header[i]) {
                '(' -> paren++
                ')' -> paren--
                ':' -> if (paren == 0) { colon = i; i = header.length }
            }
            i++
        }
        if (colon == -1) return emptyList()
        val rest = cutTopLevel(header.substring(colon + 1), '{')
        return splitTopLevel(rest, ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                entry.substringBefore('(').substringBefore('<').trim().substringAfterLast('.')
            }
            .filter { it.isNotEmpty() && it.first().isUpperCase() }
    }

    private fun visibilityOf(trimmed: String): String? {
        for (v in listOf("private", "internal", "protected", "public")) {
            if (Regex("""\b$v\b""").containsMatchIn(trimmed)) return v
        }
        return null
    }

    private fun locateCtorPropLine(startLine: Int, endLine: Int, name: String): Int {
        val pattern = Regex("""\b(val|var)\s+$name\b""")
        for (i in startLine..endLine) {
            if (pattern.containsMatchIn(maskedLines[i])) return i
        }
        return startLine
    }

    // --- enum entries -----------------------------------------------------------

    private fun parseEnumEntries(masked: String, lineIdx: Int, enumNode: Node): List<Node> {
        val trimmed = masked.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (Regex("""\b(fun|val|var|class|interface|object|constructor|init|typealias|companion)\b""")
                .containsMatchIn(trimmed)) return emptyList()
        if (trimmed.startsWith("@")) return emptyList()

        val entries = mutableListOf<Node>()
        val parts = splitTopLevel(trimmed.removeSuffix(";"), ',')
        var searchFrom = masked.indexOf(trimmed).coerceAtLeast(0)
        for (part in parts) {
            val p = part.trim()
            val m = Regex("""^(\w+)\s*(\(.*\))?$""").find(p) ?: continue
            val name = m.groupValues[1]
            val col = masked.indexOf(name, startIndex = searchFrom)
            if (col == -1) continue
            entries.add(
                Node(
                    name = name, nameCol = col, kind = SymbolKind.EnumMember,
                    startLine = lineIdx, headerEndLine = lineIdx,
                    signature = name, doc = null, visibility = null,
                    parameters = emptyList(), returnType = null, superTypes = emptyList(),
                    parent = enumNode,
                )
            )
            searchFrom = col + name.length
        }
        return entries
    }

    // --- references ---------------------------------------------------------------

    private fun collectCallReferences(i: Int) {
        val masked = maskedLines[i]
        if (masked.isBlank()) return

        val enclosing = bodyStack.lastOrNull()?.node
        val enclosingName = enclosing?.let { n ->
            // qualified name will be assigned at build time; use simple chain here
            generateSequence(n) { it.parent }.toList()
                .filter { it !== root }
                .reversed()
                .joinToString(".") { it.name }
        }

        val regex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
        for (m in regex.findAll(masked)) {
            val name = m.groupValues[1]
            if (name in CALL_EXCLUSIONS) continue
            val col = m.range.first
            references.add(
                Symbol(
                    name = name,
                    kind = SymbolKind.Function,
                    signature = name,
                    range = lineRange(i),
                    nameRange = Range(Position(i, col), Position(i, col + name.length)),
                    isDeclaration = false,
                    containerName = enclosingName,
                )
            )
        }
    }

    private fun lineRange(line: Int): Range =
        Range(Position(line, 0), Position(line, 10000))

    companion object {
        private val CLASS_LIKE_KINDS = setOf(
            SymbolKind.Class, SymbolKind.Interface, SymbolKind.Object, SymbolKind.Enum,
        )

        private val ANN = """(?:@[\w.:]+(?:\([^()]*\))?\s+)*"""
        private val MODS =
            """(?:(?:public|private|internal|protected|abstract|open|final|inline|infix|operator|tailrec|external|suspend|expect|actual|override|reified|sealed|data|inner|annotation|enum|companion|const|lateinit|value)\s+)*"""

        private val FUN = Regex(
            """^\s*$ANN$MODS""" + """fun\s+(?:<[^>]*>\s*)?(?:[\w.$]+(?:<[^>]*>)?\s*\.\s*)*(\w+)\s*\("""
        )
        private val CLASS_LIKE = Regex(
            """^\s*$ANN$MODS""" + """(class|interface|object)\s+(\w*)"""
        )
        private val TYPEALIAS = Regex(
            """^\s*$ANN$MODS""" + """typealias\s+(\w+)"""
        )
        private val CONSTRUCTOR = Regex(
            """^\s*$MODS""" + """constructor\s*\("""
        )
        private val VAL_VAR = Regex(
            """^\s*$ANN$MODS""" + """(?:val|var)\s+(?:[\w.$]+\.\s*)*(\w+)"""
        )
        private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""")

        private val CALL_EXCLUSIONS = setOf(
            "if", "for", "while", "when", "return", "catch", "constructor", "init",
            "class", "interface", "object", "fun", "super", "this", "get", "set",
        )

        private fun collectBlockDoc(lines: List<String>, start: Int): String? {
            val sb = StringBuilder()
            var i = start
            while (i < lines.size) {
                val l = lines[i].trim()
                if (l.startsWith("/**")) {
                    val inline = l.removePrefix("/**")
                    val content = inline.removePrefix("*").removeSuffix("*/").trim()
                    if (content.isNotBlank()) sb.append(content).append(' ')
                    if (l.contains("*/")) break
                } else if (l.startsWith("*")) {
                    val rest = l.removePrefix("*").trim()
                    if (rest.startsWith("/")) break
                    val content = rest.removeSuffix("*/").trim()
                    if (content.isNotBlank()) sb.append(content).append(' ')
                    if (l.endsWith("*/")) break
                } else break
                i++
            }
            return sb.toString().trim().ifEmpty { null }
        }
    }
}
