package com.androidstudioexperience.lsp.index

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves the real compile classpath of a Gradle project by running a
 * lightweight init script through the project's own Gradle wrapper.
 *
 * The init script registers a task (`aseDumpClasspath`) on every project that
 * prints, for each resolvable `*CompileClasspath` configuration (main sources
 * only, no test/metadata/klib), the absolute paths of the dependency jars/aars.
 *
 * The server ships the script as a resource and materializes it under the
 * index directory before invoking the wrapper.
 */
class ClasspathResolver(
    private val indexDir: File
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scriptName = "ase-dump-classpath.init.gradle.kts"

    /**
     * Resolves classpath jars/aars for [projectRoot]. Returns empty list on any
     * failure (graceful degradation — external indexing is best effort).
     */
    fun resolve(projectRoot: String, timeoutSeconds: Long = 180): List<File> {
        val root = File(projectRoot)
        val gradlew = findWrapper(root) ?: run {
            logger.warn("No Gradle wrapper found under $projectRoot — skipping external lib indexing")
            return emptyList()
        }

        val script = materializeScript() ?: return emptyList()

        val cmd = buildList {
            add(gradlew.absolutePath)
            add("aseDumpClasspath")
            add("--init-script")
            add(script.absolutePath)
            add("--no-daemon")
            add("-q")
        }

        logger.info("Resolving classpath: ${cmd.joinToString(" ")}")
        val started = System.currentTimeMillis()
        val result = try {
            val pb = ProcessBuilder(cmd)
                .directory(root)
                .redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!ok) {
                proc.destroyForcibly()
                logger.warn("Gradle classpath resolution timed out after ${timeoutSeconds}s")
                return emptyList()
            }
            Pair(proc.exitValue(), output)
        } catch (e: Exception) {
            logger.warn("Failed to run Gradle for classpath resolution: ${e.message}")
            return emptyList()
        }
        val (exit, output) = result
        if (exit != 0) {
            logger.warn("Gradle aseDumpClasspath exited $exit; output head: ${output.take(500)}")
            return emptyList()
        }

        val jars = parseClasspathOutput(output)
        logger.info("Resolved ${jars.size} classpath files in ${System.currentTimeMillis() - started}ms")
        return jars
    }

    private fun findWrapper(root: File): File? {
        val unix = File(root, "gradlew")
        if (unix.canExecute()) return unix
        val win = File(root, "gradlew.bat")
        if (win.exists()) return win
        // Fallback to bare `gradlew` if present but not executable yet.
        if (unix.exists()) return unix
        return null
    }

    private fun materializeScript(): File? {
        val target = File(indexDir, scriptName)
        return try {
            val resource = ClasspathResolver::class.java.getResourceAsStream("/$scriptName")
                ?: return null
            indexDir.mkdirs()
            resource.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        } catch (e: Exception) {
            logger.warn("Failed to materialize init script: ${e.message}")
            null
        }
    }

    companion object {
        /**
         * Parses the `### <project>:<config>` headers followed by absolute paths.
         * Keeps only jars and aars.
         */
        fun parseClasspathOutput(output: String): List<File> {
            val files = LinkedHashMap<String, File>()
            for (line in output.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("###")) continue
                if (t.startsWith("Downloading") || t.startsWith("> ") || t.startsWith("BUILD")) continue
                if (!t.endsWith(".jar") && !t.endsWith(".aar")) continue
                val f = File(t)
                if (f.exists()) files[t] = f
            }
            return files.values.toList()
        }
    }
}