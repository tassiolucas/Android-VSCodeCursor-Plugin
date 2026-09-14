package com.androidstudioexperience.lsp.index

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Indexes Android XML resources so `R.string.x`, `R.layout.y`, `R.id.z`
 * navigate from Kotlin code to the declaring XML element — the resource
 * half of Android Studio's go-to-definition.
 *
 * Covered:
 *  - values XML elements (`<string name="...">`, `<color>`, `<dimen>`,
 *    `<style>`, `<plurals>`, `<bool>`, `<integer>`, `<array>`, `<attr>`, ...)
 *  - file-based resources (`res/layout/foo.xml` -> layout/foo, drawable,
 *    mipmap-*, menu, raw, xml, anim, animator, font, navigation)
 *  - `@+id/name` declarations inside layout/menu XML
 *
 * Entries with the same (type, name) in different configurations
 * (values/, values-pt/, ...) are all kept, like Android Studio's list.
 */
class ResourceIndexer(
    private val index: RocksDbIndex,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Indexes all `res/` directories of a module. Returns entry count. */
    fun indexModuleResources(moduleDir: File, moduleName: String?): Int {
        var count = 0
        val srcDir = File(moduleDir, "src")
        if (!srcDir.isDirectory) return 0
        srcDir.walkTopDown()
            .filter { it.isDirectory && it.name == "res" }
            .forEach { resDir -> count += indexResDir(resDir, moduleName) }
        return count
    }

    private fun indexResDir(resDir: File, moduleName: String?): Int {
        var count = 0
        resDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file -> count += indexResourceFile(file, moduleName) }
        return count
    }

    /** Re-indexes a single resource XML file (incremental path). */
    fun indexResourceFile(file: File, moduleName: String?): Int {
        if (file.extension != "xml") return 0
        val resDir = findResDir(file) ?: return 0
        val qualifierDir = file.parentFile?.name ?: return 0
        val entries = mutableListOf<ResourceEntry>()

        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            logger.warn("Failed to read ${file.absolutePath}: ${e.message}")
            return 0
        }

        if (qualifierDir == "values" || qualifierDir.startsWith("values-")) {
            // <string name="app_name">... — type is the tag name.
            val pattern = Regex("""<(\w[\w-]*)\s+[^>]*\bname\s*=\s*"([^"]+)"""")
            for ((i, line) in lines.withIndex()) {
                for (m in pattern.findAll(line)) {
                    val tag = m.groupValues[1]
                    if (tag == "resources" || tag == "eat-comment" || tag == "skip") continue
                    val name = m.groupValues[2]
                    val col = line.indexOf("name=", m.range.first).let { idx ->
                        if (idx >= 0) line.indexOf('"', idx) + 1 else m.range.first
                    }
                    entries += ResourceEntry(normalizeValueType(tag), name, file.absolutePath, i, col, moduleName)
                }
            }
        } else {
            // File-based resource: res/layout/activity_main.xml -> layout/activity_main
            val type = qualifierDir.substringBefore('-')
            val name = file.nameWithoutExtension
            entries += ResourceEntry(type, name, file.absolutePath, 0, 0, moduleName)
        }

        // @+id/ declarations anywhere under res/.
        val idPattern = Regex("""@\+id/(\w+)""")
        for ((i, line) in lines.withIndex()) {
            for (m in idPattern.findAll(line)) {
                entries += ResourceEntry(
                    "id", m.groupValues[1], file.absolutePath, i,
                    m.groups[1]!!.range.first, moduleName,
                )
            }
        }

        // Replace all entries contributed by this file.
        index.removeResourcesOfFile(file.absolutePath)
        for (e in entries) index.putResource(e)
        if (entries.isNotEmpty()) {
            logger.debug("Indexed ${entries.size} resources from ${file.name}")
        }
        return entries.size
    }

    private fun findResDir(file: File): File? {
        var dir = file.parentFile
        while (dir != null) {
            if (dir.name == "res") return dir
            if (dir.name == "src") return null
            dir = dir.parentFile
        }
        return null
    }

    private fun normalizeValueType(tag: String): String = when (tag) {
        "string-array", "integer-array", "array" -> "array"
        "declare-styleable" -> "styleable"
        else -> tag
    }
}

/** One Android resource declaration (R.type.name -> XML location). */
data class ResourceEntry(
    val type: String,      // string, color, dimen, layout, drawable, id, ...
    val name: String,      // app_name, activity_main, ...
    val filePath: String,
    val line: Int,
    val col: Int,
    val moduleName: String?,
) {
    fun toLocation(): org.eclipse.lsp4j.Location =
        org.eclipse.lsp4j.Location(
            SymbolInfo.filePathToUri(filePath),
            org.eclipse.lsp4j.Range(
                org.eclipse.lsp4j.Position(line, col),
                org.eclipse.lsp4j.Position(line, col + name.length),
            ),
        )
}
