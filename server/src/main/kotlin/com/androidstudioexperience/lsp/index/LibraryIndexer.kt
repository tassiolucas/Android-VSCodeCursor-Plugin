package com.androidstudioexperience.lsp.index

import org.eclipse.lsp4j.SymbolKind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile

/**
 * Indexes external libraries (`.class` inside jars, and `classes.jar` inside
 * aars) using ASM, producing [LibrarySymbol] entries for classes/interfaces/
 * enums and their members (methods/fields).
 *
 * JVM-mangled names are cleaned up heuristically:
 *  - `getX`/`setX` -> property `x`
 *  - `$default`, `$lambda$N`, `$annotations` synthetic members are dropped
 *  - anonymous/lambda classes (`Foo$1`, `Foo$2`) are skipped
 */
class LibraryIndexer {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun indexJars(jars: List<File>): List<LibrarySymbol> {
        val all = mutableListOf<LibrarySymbol>()
        for (jar in jars) {
            try {
                all.addAll(indexJar(jar))
            } catch (e: Exception) {
                logger.warn("Failed to index library ${jar.name}: ${e.message}")
            }
        }
        return all
    }

    private fun indexJar(jar: File): List<LibrarySymbol> {
        val symbols = mutableListOf<LibrarySymbol>()
        val jarPath = jar.absolutePath
        val artifact = LibrarySymbol.artifactNameFromPath(jar.name)

        if (jar.name.endsWith(".aar")) {
            // AAR contains classes.jar inside.
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("classes.jar") ?: return emptyList()
                val tmp = File.createTempFile("ase-aar", ".jar")
                try {
                    zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    symbols.addAll(readJarSymbols(tmp, jarPath, artifact))
                } finally {
                    tmp.delete()
                }
            }
            return symbols
        }

        if (jar.name.endsWith(".jar")) {
            return readJarSymbols(jar, jarPath, artifact)
        }
        return emptyList()
    }

    private fun readJarSymbols(jar: File, jarPath: String, artifact: String?): List<LibrarySymbol> {
        val symbols = mutableListOf<LibrarySymbol>()
        JarFile(jar).use { jf ->
            val entries = jf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                if (name.startsWith("META-INF/") || name.contains("module-info")) continue
                try {
                    symbols.addAll(readClass(jf.getInputStream(entry).readBytes(), name, jarPath, artifact))
                } catch (e: Exception) {
                    logger.debug("Skipping class $name in ${jar.name}: ${e.message}")
                }
            }
        }
        return symbols
    }

    private fun readClass(bytes: ByteArray, entryName: String, jarPath: String, artifact: String?): List<LibrarySymbol> {
        val reader = ClassReader(bytes)
        val symbols = mutableListOf<LibrarySymbol>()

        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            private var internalName: String = ""
            private var className: String = ""
            private var kind: SymbolKind = SymbolKind.Class

            override fun visit(
                version: Int, access: Int, name: String, signature: String?,
                superName: String?, interfaces: Array<out String>?
            ) {
                internalName = name
                className = name.replace('/', '.')
                kind = when {
                    access and Opcodes.ACC_INTERFACE != 0 -> SymbolKind.Interface
                    access and Opcodes.ACC_ANNOTATION != 0 -> SymbolKind.Interface
                    access and Opcodes.ACC_ENUM != 0 -> SymbolKind.Enum
                    else -> SymbolKind.Class
                }
                val simple = simpleName(name)
                if (shouldSkipClass(name, simple)) {
                    return
                }
                symbols.add(
                    LibrarySymbol(
                        id = LibrarySymbol.makeId(jarPath, name, null),
                        name = simple,
                        kind = kind,
                        className = name,
                        memberName = null,
                        memberDescriptor = null,
                        jarPath = jarPath,
                        artifact = artifact
                    )
                )
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                if (access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_ENUM) != 0) return null
                if (symbols.none { it.className == internalName }) return null
                val clean = cleanMemberName(name) ?: return null
                symbols.add(
                    LibrarySymbol(
                        id = LibrarySymbol.makeId(jarPath, internalName, name),
                        name = clean.name,
                        kind = SymbolKind.Property,
                        className = internalName,
                        memberName = name,
                        memberDescriptor = descriptor,
                        jarPath = jarPath,
                        artifact = artifact
                    )
                )
                return null
            }

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor? {
                if (access and Opcodes.ACC_SYNTHETIC != 0) return null
                if (name.startsWith("<") && name != "<init>") return null
                if (symbols.none { it.className == internalName }) return null
                if (name == "<init>") {
                    // Keep constructors (with their JVM descriptor) so signature
                    // help can offer `Foo(...)` candidates from libraries.
                    symbols.add(
                        LibrarySymbol(
                            id = LibrarySymbol.makeId(jarPath, internalName, "<init>$descriptor"),
                            name = simpleName(internalName),
                            kind = SymbolKind.Constructor,
                            className = internalName,
                            memberName = "<init>",
                            memberDescriptor = descriptor,
                            jarPath = jarPath,
                            artifact = artifact
                        )
                    )
                    return null
                }
                val clean = cleanMemberName(name) ?: return null
                symbols.add(
                    LibrarySymbol(
                        id = LibrarySymbol.makeId(jarPath, internalName, name),
                        name = clean.name,
                        kind = if (name == "<init>") SymbolKind.Constructor else clean.kind,
                        className = internalName,
                        memberName = if (name == "<init>") null else name,
                        memberDescriptor = descriptor,
                        jarPath = jarPath,
                        artifact = artifact
                    )
                )
                return null
            }
        }
        reader.accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return symbols
    }

    private fun simpleName(internalName: String): String {
        val last = internalName.substringAfterLast('/')
        return last.substringBefore('$')
    }

    private fun shouldSkipClass(internalName: String, simpleName: String): Boolean {
        // Skip anonymous/lambda classes (Foo$1, Foo$2, ...) — noise for navigation.
        if (Regex("\\$\\d+").containsMatchIn(internalName)) return true
        // Keep top-level file facades (FooKt) which hold top-level functions.
        return false
    }

    private data class CleanMember(val name: String, val kind: SymbolKind)

    private fun cleanMemberName(jvmName: String): CleanMember? {
        if (jvmName.startsWith("<")) return null
        if (jvmName.contains("\$default") || jvmName.contains("\$lambda") || jvmName.endsWith("\$annotations")) return null
        // getX()/setX() -> x (property)
        if (jvmName.startsWith("get") && jvmName.length > 3 && jvmName[3].isUpperCase()) {
            return CleanMember(jvmName[3].lowercaseChar() + jvmName.substring(4), SymbolKind.Property)
        }
        if (jvmName.startsWith("set") && jvmName.length > 3 && jvmName[3].isUpperCase()) {
            return CleanMember(jvmName[3].lowercaseChar() + jvmName.substring(4), SymbolKind.Property)
        }
        return CleanMember(jvmName, SymbolKind.Function)
    }
}