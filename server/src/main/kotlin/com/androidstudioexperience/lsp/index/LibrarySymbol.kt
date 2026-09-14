package com.androidstudioexperience.lsp.index

import org.eclipse.lsp4j.SymbolKind

/**
 * A symbol coming from an external library (a `.class` read from a jar/aar
 * on the project compile classpath), as opposed to project source.
 *
 * [className] is the JVM internal binary name (e.g. `io/ktor/client/HttpClient`).
 * [memberName] is the JVM member name for methods/fields (null for the class itself).
 * [jarPath] points at the jar (or the `classes.jar` inside an aar) it came from.
 */
data class LibrarySymbol(
    val id: String,
    val name: String,
    val kind: SymbolKind,
    val className: String,
    val memberName: String?,
    val memberDescriptor: String?,
    val jarPath: String,
    val artifact: String? = null
) {
    fun toLocation(): org.eclipse.lsp4j.Location {
        val uri = toUri()
        // Whole-class symbol: point at the class; member: still points at class header
        // (a real line/column only exists after decompilation — Phase 3).
        return org.eclipse.lsp4j.Location(
            uri,
            org.eclipse.lsp4j.Range(
                org.eclipse.lsp4j.Position(0, 0),
                org.eclipse.lsp4j.Position(0, 0)
            )
        )
    }

    fun toUri(): String {
        val sb = StringBuilder("ase-lib:")
        sb.append(jarPath.replace('\\', '/'))
        sb.append('!').append(className)
        if (memberName != null) sb.append('#').append(memberName)
        return sb.toString()
    }

    companion object {
        fun makeId(jarPath: String, className: String, memberName: String?): String {
            val base = "$jarPath|$className"
            return if (memberName != null) "$base#$memberName" else base
        }

        /** Human-readable name of the jar's artifact (best effort from path). */
        fun artifactNameFromPath(jarPath: String): String? {
            val name = jarPath.substringAfterLast('/').substringAfterLast('\\')
            // e.g. ktor-client-core-jvm-3.3.2.jar -> io.ktor:ktor-client-core-jvm
            val noExt = name.removeSuffix(".jar")
            val version = noExt.substringAfterLast('-').takeIf { it.matches(Regex("\\d.*")) } ?: return name
            val module = noExt.removeSuffix("-$version")
            if (module.isBlank()) return name
            // Heuristic grouping by known prefixes is intentionally skipped; the
            // Maven coordinates are not encoded in the file path.
            return "$module:$version"
        }
    }
}