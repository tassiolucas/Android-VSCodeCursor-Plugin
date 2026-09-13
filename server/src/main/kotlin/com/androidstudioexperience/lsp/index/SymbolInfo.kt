package com.androidstudioexperience.lsp.index

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolKind

/**
 * Represents a symbol indexed from a Kotlin source file.
 * Stored in RocksDB for fast cross-file lookup.
 *
 * [id] is a unique identifier: "${filePath}:${line}:${name}"
 * [kind] is the LSP SymbolKind (Function, Class, Property, etc.)
 * [filePath] is the absolute path to the source file
 * [line] is the 0-based line number where the symbol is declared
 * [col] is the 0-based column where the name starts
 * [containerName] is the enclosing class/function (if any)
 * [moduleName] is the Gradle module name (if known)
 */
data class SymbolInfo(
    val id: String,
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val line: Int,
    val col: Int,
    val containerName: String? = null,
    val moduleName: String? = null
) {
    fun toLocation(): Location {
        val uri = filePathToUri(filePath)
        return Location(
            uri,
            org.eclipse.lsp4j.Range(
                org.eclipse.lsp4j.Position(line, col),
                org.eclipse.lsp4j.Position(line, col + name.length)
            )
        )
    }

    companion object {
        fun makeId(filePath: String, line: Int, name: String): String =
            "$filePath:$line:$name"

        /**
         * Converts a Windows/Unix file path to a proper file:// URI.
         * Windows: "d:\path\file.kt" -> "file:///d:/path/file.kt"
         */
        fun filePathToUri(filePath: String): String {
            val normalized = filePath.replace('\\', '/')
            return if (!normalized.startsWith("/")) {
                "file:///$normalized"
            } else {
                "file://$normalized"
            }
        }
    }
}

/**
 * Represents a reference to a symbol (usage site).
 * Used for "find usages" functionality.
 */
data class SymbolReference(
    val symbolId: String,
    val filePath: String,
    val line: Int,
    val col: Int,
    val referenceText: String
) {
    fun toLocation(): Location {
        val uri = "file://$filePath"
        return Location(
            uri,
            org.eclipse.lsp4j.Range(
                org.eclipse.lsp4j.Position(line, col),
                org.eclipse.lsp4j.Position(line, col + referenceText.length)
            )
        )
    }
}
