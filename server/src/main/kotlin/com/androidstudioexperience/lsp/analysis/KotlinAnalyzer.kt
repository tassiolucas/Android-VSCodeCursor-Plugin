package com.androidstudioexperience.lsp.analysis

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind

/**
 * Lightweight structural Kotlin analyzer used in Phase 1.
 *
 * Extracts top-level and nested declarations (fun/class/interface/object/val/var)
 * from source text and answers hover / definition / document-symbol requests.
 *
 * ponytail: structural (regex/line based) for now. Semantic analysis via the
 * Kotlin Analysis API replaces this in Phase 2 — the public surface
 * (hover/definition/documentSymbols/findSymbols) is kept identical so the
 * LSP layer does not change when the engine is swapped.
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
        val symbol = model.symbolAt(line, character) ?: return null
        val fileName = uri.substringAfterLast('/').substringAfterLast('\\')
        return buildHover(symbol.copy(fileName = fileName))
    }

    fun definition(uri: String, text: String, line: Int, character: Int): Location? {
        val model = openDocuments[uri] ?: DocumentModel.parse(text)
        val symbol = model.symbolAt(line, character) ?: return null

        // If we're already on a declaration, return its own location.
        if (symbol.isDeclaration && symbol.range.start.line == line) {
            return locationFor(uri, symbol)
        }

        // Otherwise resolve the reference against declarations in the same
        // document (Phase 1 scope). Cross-file resolution arrives with the
        // project index in Phase 2.
        val target = model.resolveReference(symbol.name)
            ?: model.declarations[symbol.name]
            ?: return null
        return locationFor(uri, target)
    }

    fun documentSymbols(text: String): List<DocumentSymbol> =
        DocumentModel.parse(text).toDocumentSymbols()

    fun findSymbols(text: String, query: String): List<SymbolInformation> =
        DocumentModel.parse(text).declarations.values
            .filter { it.name.lowercase().contains(query) }
            .map { it.toSymbolInformation() }

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
) {
    fun toSymbolInformation(): SymbolInformation =
        SymbolInformation(name, kind, org.eclipse.lsp4j.Location("file:///$fileName", range), null)
}

data class DocumentModel(
    val declarations: Map<String, Symbol>,
    val references: List<Symbol>,
) {
    private val symbolsSorted: List<Symbol> =
        (declarations.values + references).sortedBy { it.range.start.line }

    fun symbolAt(line: Int, character: Int): Symbol? =
        symbolsSorted.firstOrNull { s ->
            s.nameRange.start.line == line &&
                character >= s.nameRange.start.character &&
                character <= s.nameRange.end.character
        }

    fun resolveReference(name: String): Symbol? = declarations[name]

    fun toDocumentSymbols(): List<DocumentSymbol> =
        declarations.values.map { s ->
            DocumentSymbol(s.name, s.kind, s.range, s.nameRange, s.signature, emptyList())
        }

    companion object {
        fun parse(text: String): DocumentModel {
            val declarations = linkedMapOf<String, Symbol>()
            val references = mutableListOf<Symbol>()
            val lines = text.lines()
            var currentDoc: String? = null

            for (i in lines.indices) {
                val line = lines[i]
                val trimmed = line.trim()

                // Collect KDoc block above a declaration
                if (trimmed.startsWith("/*")) {
                    currentDoc = collectBlockDoc(lines, i)
                } else if (trimmed.startsWith("//")) {
                    currentDoc = trimmed.removePrefix("//").trim()
                } else if (trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("import") || trimmed.startsWith("package")) {
                    // keep previous doc for annotations; skip imports/package
                    if (trimmed.startsWith("@")) {
                        // annotation line: keep doc
                    } else {
                        currentDoc = null
                    }
                }

                val decl = parseDeclaration(lines, i, currentDoc)
                if (decl != null) {
                    declarations.putIfAbsent(decl.name, decl)
                    currentDoc = null
                }

                // Collect call-site references: identifier( ... )
                collectCallReferences(lines, i, text).forEach { ref ->
                    references.add(ref)
                }
            }
            return DocumentModel(declarations, references)
        }

        private fun collectBlockDoc(lines: List<String>, start: Int): String? {
            val sb = StringBuilder()
            var i = start
            while (i < lines.size) {
                val raw = lines[i]
                val l = raw.trim()
                if (l.startsWith("/**")) {
                    // Opening line: handle inline /** ... */ or just /**
                    val inline = l.removePrefix("/**")
                    val content = inline.removePrefix("*").removeSuffix("*/").trim()
                    if (content.isNotBlank()) sb.append(content).append(' ')
                    if (l.contains("*/")) break
                } else if (l.startsWith("*")) {
                    val rest = l.removePrefix("*").trim()
                    if (rest.startsWith("/")) {
                        // Closing "*/" line — stop.
                        break
                    }
                    val content = rest.removeSuffix("*/").trim()
                    if (content.isNotBlank()) sb.append(content).append(' ')
                    if (l.endsWith("*/")) break
                } else break
                i++
            }
            return sb.toString().trim().ifEmpty { null }
        }

        private fun parseDeclaration(
            lines: List<String>,
            i: Int,
            doc: String?,
        ): Symbol? {
            val line = lines[i]
            val trimmed = line.trim()

            fun nameRange(nameMatch: MatchResult): Range {
                // Name column = leading whitespace + group-1 position within trimmed.
                val group = nameMatch.groups[1] ?: return Range(Position(i, 0), Position(i, 0))
                val col = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it } + group.range.first
                return Range(Position(i, col), Position(i, col + group.value.length))
            }

            fun declSymbol(nameMatch: MatchResult, kind: SymbolKind, sig: String): Symbol =
                Symbol(
                    name = nameMatch.groupValues[1],
                    kind = kind,
                    signature = sig,
                    range = lineRange(i),
                    nameRange = nameRange(nameMatch),
                    isDeclaration = true,
                    doc = doc,
                )

            when {
                trimmed.startsWith("fun ") -> {
                    val nameMatch = Regex("fun\\s+(\\w+)").find(trimmed) ?: return null
                    return declSymbol(nameMatch, SymbolKind.Function, trimmed.substringBefore("{").trim())
                }
                trimmed.startsWith("class ") -> {
                    val nameMatch = Regex("class\\s+(\\w+)").find(trimmed) ?: return null
                    return declSymbol(nameMatch, SymbolKind.Class, trimmed.substringBefore("{").trim())
                }
                trimmed.startsWith("interface ") -> {
                    val nameMatch = Regex("interface\\s+(\\w+)").find(trimmed) ?: return null
                    return declSymbol(nameMatch, SymbolKind.Interface, trimmed.substringBefore("{").trim())
                }
                trimmed.startsWith("object ") -> {
                    val nameMatch = Regex("object\\s+(\\w+)").find(trimmed) ?: return null
                    return declSymbol(nameMatch, SymbolKind.Object, trimmed.substringBefore("{").trim())
                }
                trimmed.startsWith("enum class ") -> {
                    val nameMatch = Regex("enum class\\s+(\\w+)").find(trimmed) ?: return null
                    return declSymbol(nameMatch, SymbolKind.Enum, trimmed.substringBefore("{").trim())
                }
                (trimmed.startsWith("val ") || trimmed.startsWith("var ")) -> {
                    val nameMatch = Regex("(?:val|var)\\s+(\\w+)").find(trimmed) ?: return null
                    return declSymbol(nameMatch, SymbolKind.Property, trimmed.substringBefore("=").trim())
                }
            }
            return null
        }

        private fun collectCallReferences(
            lines: List<String>,
            i: Int,
            text: String,
        ): List<Symbol> {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return emptyList()

            // For declaration lines, skip the declaration header itself; only
            // collect calls in the expression body (e.g. "fun f() = g()").
            val searchText = when {
                trimmed.startsWith("fun ") -> {
                    // Take everything after the declaration signature: body after '=' or '{'
                    val afterFun = trimmed.removePrefix("fun ")
                    val sigEnd = afterFun.indexOf('{').let { if (it == -1) afterFun.length else it }
                    afterFun.substring(0, sigEnd)
                        .substringAfter('=', "")
                        .trim()
                }
                else -> trimmed
            }
            if (searchText.isEmpty()) return emptyList()

            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
            // Column offset of searchText within the original line.
            val searchOffset = trimmed.indexOf(searchText).let { if (it == -1) 0 else it }

            val refs = mutableListOf<Symbol>()
            val regex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
            for (m in regex.findAll(searchText)) {
                if (m.groupValues[1] in setOf("if", "for", "while", "when", "return", "catch")) continue
                val col = indent + searchOffset + m.range.first
                refs.add(
                    Symbol(
                        name = m.groupValues[1],
                        kind = SymbolKind.Function,
                        signature = m.groupValues[1],
                        range = lineRange(i),
                        nameRange = Range(Position(i, col), Position(i, col + m.value.length)),
                        isDeclaration = false,
                    )
                )
            }
            return refs
        }

        private fun lineRange(line: Int): Range =
            Range(Position(line, 0), Position(line, 10000))
    }
}