package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.index.ClasspathResolver
import com.androidstudioexperience.lsp.index.LibraryIndexer
import com.androidstudioexperience.lsp.index.LibrarySymbol
import org.eclipse.lsp4j.SymbolKind
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryIndexerTest {

    @Test
    fun `indexes classes and members from a jar`() {
        val jar = buildSampleJar()
        val symbols = LibraryIndexer().indexJars(listOf(jar))
        jar.delete()

        val greeter = symbols.single { it.className == "com/example/lib/Greeter" && it.memberName == null }
        assertEquals("Greeter", greeter.name)
        assertEquals(SymbolKind.Class, greeter.kind)

        // getTitle() getter -> property `title`
        assertTrue(symbols.any { it.name == "title" && it.kind == SymbolKind.Property }, "getter cleaned to property")
        // sayHello() -> function
        assertTrue(symbols.any { it.name == "sayHello" && it.kind == SymbolKind.Function }, "method indexed")

        // Top-level facade GreeterKt holds top-level functions
        assertTrue(symbols.any { it.className == "com/example/lib/GreeterKt" && it.memberName == null }, "file facade indexed")
        assertTrue(symbols.any { it.className == "com/example/lib/GreeterKt" && it.name == "greet" }, "top-level fun indexed")

        // Anonymous/lambda classes skipped
        assertTrue(symbols.none { it.className.contains("Greeter\$1") }, "anonymous classes skipped")
        // Synthetic members dropped
        assertTrue(symbols.none { it.name.contains("default") || it.name.contains("lambda") }, "synthetic members dropped")
    }

    @Test
    fun `library location uses ase-lib uri`() {
        val jar = buildSampleJar()
        val symbols = LibraryIndexer().indexJars(listOf(jar))
        jar.delete()
        val greeter = symbols.single { it.className == "com/example/lib/Greeter" && it.memberName == null }
        assertTrue(greeter.toUri().startsWith("ase-lib:"))
        assertTrue(greeter.toUri().contains("com/example/lib/Greeter"))
    }

    @Test
    fun `classpath output parser keeps existing jars`() {
        val existing = java.io.File.createTempFile("some-lib", ".jar")
        val output = """
            ### :shared:androidCompileClasspath
            /nonexistent/path/foo.jar
            ${existing.absolutePath}
            ### :shared:allSourceSetsCompileDependenciesMetadata
            /another/missing.jar
            BUILD SUCCESSFUL
        """.trimIndent()

        val files = ClasspathResolver.parseClasspathOutput(output)
        assertEquals(listOf(existing.absolutePath), files.map { it.absolutePath })
    }

    private fun buildSampleJar(): File {
        val jar = java.io.File.createTempFile("ase-lib-test", ".jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("com/example/lib/Greeter.class"))
            jos.write(classBytes("com/example/lib/Greeter"))
            jos.closeEntry()

            jos.putNextEntry(JarEntry("com/example/lib/GreeterKt.class"))
            jos.write(fileFacadeBytes())
            jos.closeEntry()

            jos.putNextEntry(JarEntry("com/example/lib/Greeter\$1.class"))
            jos.write(classBytes("com/example/lib/Greeter\$1"))
            jos.closeEntry()
        }
        return jar
    }

    private fun classBytes(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)

        val field = cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null)
        field.visitEnd()

        val getter: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "getTitle", "()Ljava/lang/String;", null, null)
        getter.visitCode()
        getter.visitLdcInsn("hello")
        getter.visitInsn(Opcodes.ARETURN)
        getter.visitMaxs(1, 1)
        getter.visitEnd()

        val method: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "sayHello", "()V", null, null)
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 1)
        method.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fileFacadeBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "com/example/lib/GreeterKt", null, "java/lang/Object", null)

        val method: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null)
        method.visitCode()
        method.visitVarInsn(Opcodes.ALOAD, 0)
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(1, 1)
        method.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }
}