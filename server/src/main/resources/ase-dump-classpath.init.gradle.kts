allprojects {
    tasks.register("aseDumpClasspath") {
        doLast {
            val seen = LinkedHashSet<String>()
            configurations.forEach { cfg ->
                if (!cfg.name.endsWith("CompileClasspath")) return@forEach
                if (cfg.name.contains("Test")) return@forEach
                if (cfg.name.contains("Metadata")) return@forEach
                if (cfg.name.contains("Klib")) return@forEach
                val files = try { cfg.files } catch (e: Exception) { null } ?: return@forEach
                val paths = files.map { it.absolutePath }.filter { seen.add(it) }
                if (paths.isNotEmpty()) {
                    println("### ${project.path}:${cfg.name}")
                    paths.forEach { println(it) }
                }
            }
        }
    }
}