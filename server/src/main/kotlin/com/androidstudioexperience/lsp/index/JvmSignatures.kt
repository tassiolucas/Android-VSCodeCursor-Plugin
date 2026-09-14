package com.androidstudioexperience.lsp.index

/**
 * Translates JVM bytecode descriptors (`(Ljava/lang/String;I)V`) into
 * Kotlin-flavored readable signatures for hover and signature help.
 *
 * Descriptors carry erased types only (no generics, no parameter names), so
 * parameters are named p0, p1, ... and types use simple names.
 */
object JvmSignatures {

    data class MethodSig(val params: List<String>, val returnType: String)

    fun parseMethod(descriptor: String): MethodSig? {
        if (!descriptor.startsWith("(")) return null
        val params = mutableListOf<String>()
        var i = 1
        while (i < descriptor.length && descriptor[i] != ')') {
            val (type, next) = parseType(descriptor, i) ?: return null
            params.add(type)
            i = next
        }
        if (i >= descriptor.length) return null
        val (ret, _) = parseType(descriptor, i + 1) ?: return null
        return MethodSig(params, ret)
    }

    fun parseField(descriptor: String): String? =
        parseType(descriptor, 0)?.first

    /** Parses one type at [start]; returns (readable type, index after the type). */
    private fun parseType(desc: String, start: Int): Pair<String, Int>? {
        if (start >= desc.length) return null
        return when (val c = desc[start]) {
            'B' -> "Byte" to start + 1
            'C' -> "Char" to start + 1
            'D' -> "Double" to start + 1
            'F' -> "Float" to start + 1
            'I' -> "Int" to start + 1
            'J' -> "Long" to start + 1
            'S' -> "Short" to start + 1
            'Z' -> "Boolean" to start + 1
            'V' -> "Unit" to start + 1
            'L' -> {
                val end = desc.indexOf(';', start)
                if (end == -1) return null
                val fqn = desc.substring(start + 1, end)
                fqn.substringAfterLast('/').replace('$', '.') to end + 1
            }
            '[' -> {
                val (element, next) = parseType(desc, start + 1) ?: return null
                "Array<$element>" to next
            }
            else -> null
        }
    }

    /** Builds a Kotlin-looking signature for a library symbol. */
    fun readableSignature(sym: LibrarySymbol): String {
        val classSimple = sym.className.substringAfterLast('/').replace('$', '.')
        val desc = sym.memberDescriptor
        return when {
            sym.memberName == null && sym.kind == org.eclipse.lsp4j.SymbolKind.Constructor -> {
                val parsed = desc?.let { parseMethod(it) }
                val params = parsed?.params?.mapIndexed { i, t -> "p$i: $t" }?.joinToString(", ") ?: ""
                "constructor($params)"
            }
            sym.memberName == null -> "${sym.kind.name.lowercase()} $classSimple"
            desc != null && desc.startsWith("(") -> {
                val parsed = parseMethod(desc)
                if (parsed != null) {
                    val params = parsed.params.mapIndexed { i, t -> "p$i: $t" }.joinToString(", ")
                    "fun $classSimple.${sym.memberName}($params): ${parsed.returnType}"
                } else {
                    "fun $classSimple.${sym.memberName}(...)"
                }
            }
            desc != null -> {
                val type = parseField(desc)
                "val $classSimple.${sym.memberName}${type?.let { ": $it" } ?: ""}"
            }
            else -> "${sym.kind.name.lowercase()} $classSimple.${sym.memberName}"
        }
    }
}
