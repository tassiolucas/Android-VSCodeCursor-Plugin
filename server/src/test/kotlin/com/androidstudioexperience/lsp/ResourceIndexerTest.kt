package com.androidstudioexperience.lsp

import com.androidstudioexperience.lsp.index.ResourceIndexer
import com.androidstudioexperience.lsp.index.RocksDbIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResourceIndexerTest {

    @Test
    fun `indexes values, layouts and ids`() {
        val indexDir = kotlin.io.path.createTempDirectory("ase-res-index").toFile()
        val index = RocksDbIndex.openOrCreate(indexDir.absolutePath) ?: error("no rocksdb")
        val root = kotlin.io.path.createTempDirectory("ase-res-proj").toFile()
        try {
            val res = root.resolve("app/src/main/res")
            res.resolve("values").mkdirs()
            res.resolve("layout").mkdirs()

            res.resolve("values/strings.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">Fruitties</string>
                    <color name="brand">#FF0000</color>
                </resources>
                """.trimIndent()
            )
            res.resolve("layout/activity_main.xml").writeText(
                """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <androidx.recyclerview.widget.RecyclerView
                        android:id="@+id/recyclerView" />
                </LinearLayout>
                """.trimIndent()
            )

            val indexer = ResourceIndexer(index)
            val count = indexer.indexModuleResources(root.resolve("app"), "app")
            assertTrue(count >= 4, "expected >= 4 resources, got $count")

            val appName = index.findResources("string", "app_name")
            assertEquals(1, appName.size)
            assertEquals(2, appName[0].line)
            assertEquals("app", appName[0].moduleName)

            val brand = index.findResources("color", "brand")
            assertEquals(1, brand.size)

            val layout = index.findResources("layout", "activity_main")
            assertEquals(1, layout.size)
            assertTrue(layout[0].filePath.endsWith("activity_main.xml"))

            val id = index.findResources("id", "recyclerView")
            assertEquals(1, id.size)
            assertEquals(2, id[0].line)

            // Re-index removes stale entries from the file.
            res.resolve("values/strings.xml").writeText(
                """<resources><string name="other">X</string></resources>"""
            )
            indexer.indexResourceFile(res.resolve("values/strings.xml"), "app")
            assertTrue(index.findResources("string", "app_name").isEmpty())
            assertNotNull(index.findResources("string", "other").singleOrNull())
        } finally {
            index.close()
            root.deleteRecursively()
            indexDir.deleteRecursively()
        }
    }
}
