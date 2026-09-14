package com.androidstudioexperience.lsp.util

/**
 * URI <-> path helpers with cross-platform normalization.
 *
 * On Linux/macOS a `file:///` URI must decode back to an ABSOLUTE path
 * (leading `/`). The naive `removePrefix("file:///")` drops that slash and
 * yields a relative path resolved against the process CWD, which breaks
 * indexing and cross-file definition.
 */
object Uris {

    fun uriToPath(uri: String): String? = try {
        if (uri.startsWith("file:///")) {
            val decoded = java.net.URLDecoder.decode(uri.removePrefix("file:///"), "UTF-8")
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            when {
                isWindows -> decoded.replace('/', '\\')
                !decoded.startsWith("/") -> "/$decoded"
                else -> decoded
            }
        } else {
            uri
        }
    } catch (e: Exception) {
        null
    }
}