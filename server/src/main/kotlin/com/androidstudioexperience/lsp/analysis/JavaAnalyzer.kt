package com.androidstudioexperience.lsp.analysis

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.util.IdentityHashMap

/**
 * Lightweight structural analyzer for Java sources — class/interface/enum
 * declarations, methods, constructors and fields with signatures, so mixed
 * Kotlin+Java Android projects get the same navigation/indexing experience.
 *
 * Reuses [KotlinMask] (// and slash-star comments, strings, chars lex the
 * same in Java). Structural only: no generics resolution, no overload
 * disambiguation.
 */
class JavaAnalyzer {

    fun topLevelSymbols(text: String): List<Symbol> = Parser(text).parse()

    private class Parser(text: String) {
        private val originalLines = text.lines()
        private val maskedLines = KotlinMask.maskLines(text)
        private val depthStart: IntArray
        private val depthEnd: IntArray

        private class Node(
            val name: String,
            val nameCol: Int,
            val kind: SymbolKind,
            val startLine: Int,
            val signature: String,
            val doc: String?,
            val visibility: String?,
            val parameters: List<KotlinParam>,
            val returnType: String?,
            val superTypes: List<String>,
            val parent: Node?,
        ) {
            val children = mutableListOf<Node>()
            var bodyEndLine: Int = startLine
        }

        private data class Pending(val node: Node, val bodyDepth: Int, val isEnum: Boolean, var seenSemi: Boolean)

        private val root = Node("", 0, SymbolKind.File, -1, "", null, null, emptyList(), null, emptyList(), null)
        private val bodyStack = ArrayDeque<Pending>()
        private var packageName: String? = null

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
                if (depth < 0) depth = 0
                depthEnd[i] = depth
            }
            depthStart[n] = depth
            depthEnd[n] = depth
        }

        fun parse(): List<Symbol> {
            var currentDoc: String? = null
            var i = 0
            val n = originalLines.size
            while (i < n) {
                val masked = maskedLines[i]
                val trimmed = masked.trim()
                while (bodyStack.isNotEmpty() && depthStart[i] < bodyStack.last().bodyDepth) {
                    val closed = bodyStack.removeLast()
                    closed.node.bodyEndLine = (i - 1).coerceAtLeast(closed.node.startLine)
                }

                val orig = originalLines[i].trim()
                if (orig.startsWith("/*")) {
                    currentDoc = collectBlockDoc(originalLines, i)
                } else if (orig.startsWith("//")) {
                    currentDoc = orig.removePrefix("//").trim()
                } else if (orig.startsWith("@")) {
                    // keep doc
                } else if (orig.startsWith("*")) {
                    // inside javadoc
                } else if (orig.isEmpty()) {
                    currentDoc = null
                }

                if (packageName == null) {
                    Regex("""^\s*package\s+([\w.]+)""").find(masked)?.let { packageName = it.groupValues[1] }
                }

                if (trimmed.isEmpty()) { i++; continue }

                // Enum constants zone.
                val top = bodyStack.lastOrNull()
                if (top != null && top.isEnum && !top.seenSemi && trimmed.isNotEmpty()) {
                    if (trimmed.contains(';')) top.seenSemi = true
                    val entries = parseEnumConstants(masked, i, top.node)
                    if (entries.isNotEmpty()) {
                        top.node.children.addAll(entries)
                        i++
                        continue
                    }
                }

                val node = matchDeclaration(i, currentDoc)
                if (node != null) {
                    currentDoc = null
                    val opensBody = headerOpensBody(i)
                    if (opensBody.first) {
                        bodyStack.addLast(
                            Pending(node, opensBody.second, node.kind == SymbolKind.Enum, seenSemi = false)
                        )
                    }
                    i++
                    continue
                }
                i++
            }
            while (bodyStack.isNotEmpty()) {
                val closed = bodyStack.removeLast()
                closed.node.bodyEndLine = n - 1
            }
            return root.children.map { build(it) }
        }

        private fun matchDeclaration(lineIdx: Int, doc: String?): Node? {
            val line = maskedLines[lineIdx]
            val trimmed = line.trim()
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
            val parent = bodyStack.lastOrNull()?.node

            // class / interface / enum / @interface
            val classMatch = CLASS.find(trimmed)
            if (classMatch != null) {
                val keyword = classMatch.groupValues[1]
                val name = classMatch.groupValues[2]
                val kind = when {
                    keyword == "@interface" || keyword == "interface" -> SymbolKind.Interface
                    keyword == "enum" -> SymbolKind.Enum
                    else -> SymbolKind.Class
                }
                val header = headerToBrace(lineIdx)
                val node = Node(
                    name = name,
                    nameCol = indent + classMatch.groups[2]!!.range.first,
                    kind = kind,
                    startLine = lineIdx,
                    signature = header.trim(),
                    doc = doc,
                    visibility = visibilityOf(trimmed),
                    parameters = emptyList(),
                    returnType = null,
                    superTypes = superTypesOf(header),
                    parent = parent,
                )
                parent?.children?.add(node) ?: root.children.add(node)
                return node
            }

            // method / constructor / field — only directly in a type body or top level (records)
            val inTypeBody = parent != null && parent.kind in CLASS_KINDS
            if (!inTypeBody) return null

            val member = MEMBER.find(trimmed) ?: return null
            val typePart = member.groupValues[1].trim()
            val name = member.groupValues[2]
            val afterName = trimmed.substring(member.range.last + 1)

            // Method or constructor: name followed by '('
            if (afterName.trimStart().startsWith("(")) {
                val isCtor = name == parent!!.name
                val header = headerToBraceOrSemi(lineIdx)
                val params = extractParenBlock(header)?.let { parseJavaParams(it) } ?: emptyList()
                val node = Node(
                    name = name,
                    nameCol = indent + member.groups[2]!!.range.first,
                    kind = if (isCtor) SymbolKind.Constructor else SymbolKind.Method,
                    startLine = lineIdx,
                    signature = header.removeSuffix(";").trim(),
                    doc = doc,
                    visibility = visibilityOf(trimmed),
                    parameters = params,
                    returnType = if (isCtor) null else typePart,
                    superTypes = emptyList(),
                    parent = parent,
                )
                parent?.children?.add(node)
                return node
            }

            // Field: `Type name;` or `Type name = ...;`
            if (afterName.trimStart().startsWith(";") || afterName.trimStart().startsWith("=")) {
                val node = Node(
                    name = name,
                    nameCol = indent + member.groups[2]!!.range.first,
                    kind = SymbolKind.Property,
                    startLine = lineIdx,
                    signature = trimmed.removeSuffix(";").trim(),
                    doc = doc,
                    visibility = visibilityOf(trimmed),
                    parameters = emptyList(),
                    returnType = typePart.ifBlank { null },
                    superTypes = emptyList(),
                    parent = parent,
                )
                parent?.children?.add(node)
                return node
            }

            return null
        }

        /** True + body depth when the declaration line opens a `{` body. */
        private fun headerOpensBody(lineIdx: Int): Pair<Boolean, Int> {
            val masked = maskedLines[lineIdx]
            return if (masked.contains('{')) {
                true to depthEnd[lineIdx].coerceAtLeast(depthStart[lineIdx] + 1)
            } else {
                false to 0
            }
        }

        private fun headerToBrace(lineIdx: Int): String {
            val masked = maskedLines[lineIdx]
            val brace = masked.indexOf('{')
            return (if (brace >= 0) masked.substring(0, brace) else masked).trim()
        }

        private fun headerToBraceOrSemi(lineIdx: Int): String {
            val masked = maskedLines[lineIdx]
            val cut = listOf(masked.indexOf('{'), masked.indexOf(") throws"))
                .filter { it >= 0 }.minOrNull() ?: masked.length
            return masked.substring(0, cut).trim()
        }

        private fun extractParenBlock(header: String): String? {
            val open = header.indexOf('(')
            if (open == -1) return null
            var depth = 0
            for (i in open until header.length) {
                when (header[i]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return header.substring(open + 1, i) }
                }
            }
            return null
        }

        private fun parseJavaParams(block: String): List<KotlinParam> {
            if (block.isBlank()) return emptyList()
            return block.split(',').mapNotNull { raw ->
                val part = raw.trim()
                if (part.isEmpty()) return@mapNotNull null
                val m = Regex("""(?:@[\w.]+\s*)*(?:final\s+)?([\w.<>\[\]]+(?:\.\.\.)?)\s+(\w+)$""").find(part)
                    ?: return@mapNotNull null
                KotlinParam(m.groupValues[2], m.groupValues[1], null)
            }
        }

        private fun superTypesOf(header: String): List<String> {
            val result = mutableListOf<String>()
            val extends = Regex("""\bextends\s+([\w.]+)""").find(header)
            if (extends != null) result += extends.groupValues[1].substringAfterLast('.')
            val implements = Regex("""\bimplements\s+([\w.,\s]+?)(?:\{|$)""").find(header)
            if (implements != null) {
                result += implements.groupValues[1].split(',')
                    .map { it.trim().substringAfterLast('.') }
                    .filter { it.isNotEmpty() }
            }
            return result.filter { it.firstOrNull()?.isUpperCase() == true }
        }

        private fun visibilityOf(trimmed: String): String? {
            for (v in listOf("private", "protected", "public")) {
                if (Regex("""\b$v\b""").containsMatchIn(trimmed)) return v
            }
            return null
        }

        private fun parseEnumConstants(masked: String, lineIdx: Int, enumNode: Node): List<Node> {
            val trimmed = masked.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (Regex("""\b(class|interface|enum|void|static|final|public|private|protected|return|new)\b""")
                    .containsMatchIn(trimmed)) return emptyList()
            if (trimmed.startsWith("@")) return emptyList()

            val entries = mutableListOf<Node>()
            var searchFrom = masked.indexOf(trimmed).coerceAtLeast(0)
            for (part in trimmed.removeSuffix(";").removeSuffix(",").split(',')) {
                val p = part.trim().removeSuffix(";")
                val m = Regex("""^(\w+)\s*(\(.*\))?$""").find(p) ?: continue
                val name = m.groupValues[1]
                val col = masked.indexOf(name, startIndex = searchFrom)
                if (col == -1) continue
                entries += Node(
                    name, col, SymbolKind.EnumMember, lineIdx, p, null, null,
                    emptyList(), null, emptyList(), enumNode,
                )
                searchFrom = col + name.length
            }
            return entries
        }

        private fun build(node: Node): Symbol {
            val parentSymbol = node.parent?.takeIf { it !== root }?.let { buildCache[it] }
            val qName = if (parentSymbol != null) "${parentSymbol.qualifiedName}.${node.name}" else node.name
            val symbol = Symbol(
                name = node.name,
                kind = node.kind,
                signature = node.signature,
                range = Range(Position(node.startLine, 0), Position(node.bodyEndLine, 10000)),
                nameRange = Range(Position(node.startLine, node.nameCol), Position(node.startLine, node.nameCol + node.name.length)),
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
            buildCache[node] = symbol
            return symbol.copy(children = node.children.map { build(it) })
        }

        private val buildCache = IdentityHashMap<Node, Symbol>()

        companion object {
            private val CLASS_KINDS = setOf(
                SymbolKind.Class, SymbolKind.Interface, SymbolKind.Enum, SymbolKind.Object,
            )
            private val CLASS = Regex(
                """^\s*(?:@[\w.]+\s*)*(?:(?:public|private|protected|abstract|final|static|sealed|strictfp|non-sealed)\s+)*(class|interface|enum|@interface)\s+(\w+)"""
            )
            // `Type name` prefix of a method/field declaration line.
            private val MEMBER = Regex(
                """^\s*(?:@[\w.]+\s*)*(?:(?:public|private|protected|abstract|final|static|synchronized|native|strictfp|default|transient|volatile)\s+)*([\w.$<>?\[\], ]+?)\s+(\w+)(?=\s*[(;=])"""
            )

            private fun collectBlockDoc(lines: List<String>, start: Int): String? {
                val sb = StringBuilder()
                var i = start
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.startsWith("/**")) {
                        val content = l.removePrefix("/**").removeSuffix("*/").trim()
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
}
