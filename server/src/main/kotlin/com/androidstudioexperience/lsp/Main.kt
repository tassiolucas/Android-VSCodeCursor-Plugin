package com.androidstudioexperience.lsp

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture

/**
 * Entry point for the ASE LSP server.
 *
 * The server speaks LSP over stdio (stdin/stdout), which is exactly what
 * vscode-languageclient expects when spawning a subprocess.
 */
fun main(args: Array<String>) {
    // Allow plain `java -jar server.jar` from the extension host.
    val inStream: InputStream = System.`in`
    val outStream: OutputStream = System.out

    val server = AndroidLspServer()
    val launcher: Launcher<LanguageClient> = LSPLauncher.createServerLauncher(server, inStream, outStream)
    val clientProxy: LanguageClient = launcher.remoteProxy
    server.connect(clientProxy)

    // Keep the main thread alive while the reader thread listens on stdin.
    val future: java.util.concurrent.Future<Void> = launcher.startListening()
    try {
        future.get()
    } catch (e: Exception) {
        System.err.println("[ase-lsp] server stopped: $e")
    } finally {
        server.dispose()
        try {
            System.exit(0)
        } catch (_: SecurityException) {
            // Non-fatal; process ends when main returns.
        }
    }
}
