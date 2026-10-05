package com.picpocket.app.data.store

import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DocumentStoreVersionedTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var store: DocumentStore

    @Before
    fun setUp() {
        store = DocumentStore(ApplicationProvider.getApplicationContext())
    }

    private fun documentDir(id: String): File {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        return File(File(app.filesDir, "documents"), id)
    }

    @Test
    fun `createDocument writes metadata at version 0`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        val files = documentDir(doc.id).listFiles().orEmpty()
        assertEquals(listOf("metadata.0.0.json"), files.map { it.name })
        assertEquals(0, store.metadataVersion(doc.id))
        assertEquals(0, store.metadataPassphrase(doc.id))
    }

    @Test
    fun `every edit bumps the version and leaves a single metadata file`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.addPage(doc.id, pageNumber = 1, filename = "abc123.jpg", fileSizeBytes = 10L)
        store.updateDocumentName(doc.id, "Renamed")

        val files = documentDir(doc.id).listFiles().orEmpty().map { it.name }
        assertEquals(1, files.filter { MetadataNaming.isMetadata(it) }.size)
        assertEquals(2, store.metadataVersion(doc.id))
        val current = store.readMetadata(doc.id).getOrThrow()
        assertEquals("Renamed", current.name)
    }

    @Test
    fun `removePage deletes the referenced page file`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.addPage(doc.id, pageNumber = 1, filename = "abc111.jpg", fileSizeBytes = 10L)
        store.addPage(doc.id, pageNumber = 2, filename = "abc222.jpg", fileSizeBytes = 10L)
        documentDir(doc.id).resolve("abc111.jpg").writeBytes(byteArrayOf(1))
        documentDir(doc.id).resolve("abc222.jpg").writeBytes(byteArrayOf(2))
        assertTrue(documentDir(doc.id).resolve("abc222.jpg").exists())

        store.removePage(doc.id, 2)

        assertTrue(!documentDir(doc.id).resolve("abc222.jpg").exists())
        assertTrue(documentDir(doc.id).resolve("abc111.jpg").exists())
        val pages = store.readMetadata(doc.id).getOrThrow().pages
        assertEquals(listOf(1), pages.map { it.pageNumber })
        assertEquals("abc111.jpg", pages.first().filename)
    }

    @Test
    fun `writeMetadataAt writes the exact version and passphrase`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.writeMetadataAt(doc.id, doc, 7, 3)
        assertEquals(7, store.metadataVersion(doc.id))
        assertEquals(3, store.metadataPassphrase(doc.id))
        assertTrue(documentDir(doc.id).resolve("metadata.7.3.json").exists())
        assertNull(documentDir(doc.id).listFiles().orEmpty().find { it.name == "metadata.0.0.json" })
    }

    @Test
    fun `page filename is content-addressed`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val name = store.pageFilenameFor(bytes)
        assertEquals(PageNaming.filenameFor(bytes), name)
        store.addPage(doc.id, pageNumber = 1, filename = name, fileSizeBytes = 10L)
        assertEquals(name, store.readMetadata(doc.id).getOrThrow().pages.first().filename)
    }

    @Test
    fun `concurrent appends never duplicate a page number`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        (1..20).map { index ->
            launch { store.appendPage(doc.id, "page$index.jpg", fileSizeBytes = 10L) }
        }.forEach { it.join() }

        val numbers = store.readMetadata(doc.id).getOrThrow().pages.map { it.pageNumber }
        assertEquals(20, numbers.size)
        assertEquals((1..20).toList(), numbers.sorted())
        assertEquals(20, numbers.toSet().size)
    }

    @Test
    fun `addPage reassigns a duplicate explicit page number`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.addPage(doc.id, pageNumber = 1, filename = "a.jpg", fileSizeBytes = 1L)
        store.addPage(doc.id, pageNumber = 1, filename = "b.jpg", fileSizeBytes = 1L)

        val pages = store.readMetadata(doc.id).getOrThrow().pages
        assertEquals(listOf(1, 2), pages.map { it.pageNumber })
    }

    @Test
    fun `reading renumbers non-sequential page numbers`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        doc.pages.add(StoredPage(pageNumber = 1, filename = "a.jpg", createdAt = 1L))
        doc.pages.add(StoredPage(pageNumber = 5, filename = "b.jpg", createdAt = 2L))
        store.writeMetadataAt(doc.id, doc, 1, 0)

        val pages = store.readMetadata(doc.id).getOrThrow().pages
        assertEquals(listOf(1, 2), pages.map { it.pageNumber })
    }

    @Test
    fun `removing one of two pages sharing a source keeps the file`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "shared.pdf", fileSizeBytes = 10L, kind = PageKind.PDF, pdfPageIndex = 0)
        store.appendPage(doc.id, "shared.pdf", fileSizeBytes = 10L, kind = PageKind.PDF, pdfPageIndex = 1)
        val file = documentDir(doc.id).resolve("shared.pdf")
        file.writeBytes(byteArrayOf(1))

        store.removePage(doc.id, 1)
        assertTrue("file must survive while still referenced", file.exists())

        store.removePage(doc.id, 1)
        assertTrue("file deleted when the last reference is removed", !file.exists())
    }

    @Test
    fun `replacePageImage converts a native page back to an image`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "source.pdf", fileSizeBytes = 10L, kind = PageKind.PDF, pdfPageIndex = 0)

        store.replacePageImage(doc.id, 1, "new.jpg", 20L).getOrThrow()

        val page = store.readMetadata(doc.id).getOrThrow().pages.first()
        assertEquals(PageKind.IMAGE, page.kind)
        assertEquals(0, page.pdfPageIndex)
        assertEquals("new.jpg", page.filename)
    }

    @Test
    fun `totalFileSize counts a shared source once`() = runTest {
        val doc = store.createDocument("Doc").getOrThrow()
        store.appendPage(doc.id, "shared.pdf", fileSizeBytes = 100L, kind = PageKind.PDF, pdfPageIndex = 0)
        store.appendPage(doc.id, "shared.pdf", fileSizeBytes = 100L, kind = PageKind.PDF, pdfPageIndex = 1)

        assertEquals(100L, store.totalFileSize(doc.id).getOrThrow())
    }
}
