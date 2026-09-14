package com.androidstudioexperience.lsp.index

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

/**
 * Decompiles a single `.class` from an external jar/aar into Kotlin/Java
 * source using Vineflower. Used to serve navigable content for `ase-lib:` URIs
 * (go-to-definition into libraries).
 *
 * The target class is isolated into a temporary jar and added as the source;
 * the original jar is added as a library so referenced classes resolve.
 * Results are cached in memory per (jar, class).
 */
class LibraryDecompiler {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, String>()

    fun decompileClass(jarPath: String, className: String): String? {
        val key = "$jarPath|$className"
        cache[key]?.let { return it }
        val content = doDecompile(jarPath, className) ?: return null
        cache[key] = content
        return content
    }

    private fun doDecompile(jarPath: String, className: String): String? {
        val extracted = extractClass(jarPath, className) ?: run {
            logger.warn("decompile: class entry not found for $className in $jarPath")
            return null
        }
        val (bytes, libraryFile) = extracted
        if (bytes.isEmpty()) {
            logger.warn("decompile: empty bytes for $className")
            return null
        }
        logger.info("decompile: extracted ${bytes.size} bytes for $className")

        val tempJar = File.createTempFile("ase-decomp-source", ".jar")
        try {
            JarOutputStream(tempJar.outputStream()).use { jos ->
                jos.putNextEntry(JarEntry("$className.class"))
                jos.write(bytes)
                jos.closeEntry()
            }

            val saver = StringResultSaver(className)
            val decompiler = BaseDecompiler(saver, emptyMap(), IFernflowerLogger.NO_OP)
            decompiler.addSource(tempJar)
            decompiler.addLibrary(libraryFile)
            decompiler.decompileContext()
            if (saver.result == null) logger.warn("decompile: no output captured for $className")
            return saver.result
        } catch (e: Exception) {
            logger.warn("Decompilation failed for $className in $jarPath: ${e.message}")
            return null
        } finally {
            tempJar.delete()
            if (libraryFile != File(jarPath)) libraryFile.delete()
        }
    }

    /**
     * Returns (class bytes, file usable as a decompiler library).
     * For aars the nested classes.jar is materialized to a temp file.
     */
    private fun extractClass(jarPath: String, className: String): Pair<ByteArray, File>? {
        val entryName = "$className.class"
        if (jarPath.endsWith(".aar")) {
            val classesJar = File.createTempFile("ase-aar-classes", ".jar")
            try {
                ZipFile(jarPath).use { zip ->
                    val entry = zip.getEntry("classes.jar") ?: return null
                    zip.getInputStream(entry).use { input -> classesJar.outputStream().use { input.copyTo(it) } }
                }
            } catch (e: Exception) {
                classesJar.delete()
                return null
            }
            val bytes = readEntry(classesJar, entryName) ?: run { classesJar.delete(); return null }
            return Pair(bytes, classesJar)
        }
        val bytes = readEntry(File(jarPath), entryName) ?: return null
        return Pair(bytes, File(jarPath))
    }

    private fun readEntry(jar: File, entryName: String): ByteArray? = try {
        JarFile(jar).use { jf ->
            val entry = jf.getEntry(entryName) ?: return null
            jf.getInputStream(entry).readBytes()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Captures the decompiled source of the target class (matching by FQCN).
     */
    private class StringResultSaver(private val target: String) : IResultSaver {
        var result: String? = null
            private set

        override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String, mapping: IntArray?) {
            capture(qualifiedName, entryName, content)
        }

        override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String, entryName: String, content: String) {
            capture(qualifiedName, entryName, content)
        }

        override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String, entryName: String, content: String, mapping: IntArray?) {
            capture(qualifiedName, entryName, content)
        }

        private fun capture(qualifiedName: String, entryName: String, content: String) {
            val targetDots = target.replace('/', '.')
            if (qualifiedName == target || qualifiedName == targetDots ||
                entryName == "$target.class" || entryName == "$targetDots.class"
            ) {
                result = content
            }
        }

        override fun saveFolder(path: String) = Unit
        override fun copyFile(source: String, path: String, entryName: String) = Unit
        override fun createArchive(path: String, archiveName: String, manifest: java.util.jar.Manifest?) = Unit
        override fun saveDirEntry(path: String, archiveName: String, entryName: String) = Unit
        override fun copyEntry(source: String, path: String, archiveName: String, entryName: String) = Unit
        override fun closeArchive(path: String, archiveName: String) = Unit
        override fun close() = Unit
    }
}